package org.tokend.template.features.deposit.logic

import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import io.reactivex.Single
import io.reactivex.rxkotlin.toSingle
import org.tokend.rx.extensions.toSingle
import org.tokend.sdk.api.base.model.AttributesEntity
import org.tokend.sdk.api.base.model.DataEntity
import org.tokend.sdk.utils.extentions.isServerError
import org.tokend.template.data.model.AccountRecord
import org.tokend.template.features.assets.model.Asset
import org.tokend.template.data.repository.AccountRepository
import org.tokend.template.features.balances.storage.BalancesRepository
import org.tokend.template.features.systeminfo.storage.SystemInfoRepository
import org.tokend.template.di.providers.AccountProvider
import org.tokend.template.di.providers.ApiProvider
import org.tokend.template.di.providers.WalletInfoProvider
import org.tokend.template.logic.TxManager
import org.tokend.template.view.util.formatter.AmountFormatter
import retrofit2.HttpException
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.math.BigDecimal
import java.util.*

/**
 * Requests deposit address from Coinpayments and maps it to [AccountRecord.DepositAccount].
 * Asset hashcode is used as external system type
 */
class BindCoinpaymentsDepositAccountUseCase(
        asset: Asset,
        private val amount: BigDecimal,
        private val apiProvider: ApiProvider,
        private val systemInfoRepository: SystemInfoRepository,
        private val accountProvider: AccountProvider,
        private val txManager: TxManager,
        private val amountFormatter: AmountFormatter,
        walletInfoProvider: WalletInfoProvider,
        balancesRepository: BalancesRepository,
        accountRepository: AccountRepository
) : BindDepositAccountUseCase(asset.code, walletInfoProvider, balancesRepository, accountRepository) {
    private class CoinpaymentsDepositResponse(
            @SerializedName("address")
            val address: String,
            @SerializedName("timeout")
            val timeout: Long
    )

    private val mAsset = asset
    private lateinit var balanceId: String

    override fun getBoundDepositAccount(): Single<AccountRecord.DepositAccount> {
        return createBalanceIfRequired()
                .flatMap {
                    getBalanceId()
                }
                .doOnSuccess { balanceId ->
                    this.balanceId = balanceId
                }
                .flatMap {
                    getDepositAccountFromCoinpayments()
                }
    }

    private fun createBalanceIfRequired(): Single<Boolean> {
        return if (isBalanceCreationRequired)
            balancesRepository
                    .create(accountProvider, systemInfoRepository, txManager, asset)
                    .toSingleDefault(true)
                    .doOnSuccess {
                        isBalanceCreationRequired = false
                    }
        else
            Single.just(false)
    }

    private fun getBalanceId(): Single<String> {
        return balancesRepository
                .itemsList
                .find { it.assetCode == asset }
                ?.id
                ?.toSingle()
                ?: Single.error(IllegalStateException("No balance found for $asset"))
    }

    private fun getDepositAccountFromCoinpayments(): Single<AccountRecord.DepositAccount> {
        val signedApi = apiProvider.getSignedApi()
                ?: return Single.error(IllegalStateException("No signed API instance found"))

        return signedApi
                .customRequests
                .post<DataEntity<AttributesEntity<CoinpaymentsDepositResponse>>>(
                        url = "integrations/coinpayments/deposit",
                        responseType = object : ParameterizedType {
                            override fun getRawType(): Type = DataEntity::class.java

                            override fun getOwnerType(): Type? = null

                            override fun getActualTypeArguments(): Array<Type> = arrayOf(
                                    object : TypeToken<AttributesEntity<CoinpaymentsDepositResponse>>() {}
                                            .type
                            )
                        },
                        body = DataEntity(AttributesEntity(mapOf(
                                "amount" to amount,
                                "receiver" to balanceId
                        )))
                )
                .toSingle()
                .onErrorResumeNext { error ->
                    if (error is HttpException && error.isServerError())
                        Single.error(NoAvailableDepositAccountException())
                    else
                        Single.error(error)
                }
                .map {
                    val response = it.data.attributes

                    AccountRecord.DepositAccount(
                            type = asset.hashCode(),
                            expirationDate = Date(System.currentTimeMillis() +
                                    response.timeout * 1000L),
                            address = response.address,
                            payload = amountFormatter.formatAssetAmount(
                                    amount,
                                    mAsset,
                                    withAssetCode = true
                            )
                    )
                }
    }
}