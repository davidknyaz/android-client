package org.tokend.template.base.logic.repository.favorites

import io.reactivex.Completable
import io.reactivex.Single
import org.tokend.sdk.api.models.FavoriteEntry
import org.tokend.template.base.logic.di.providers.ApiProvider
import org.tokend.template.base.logic.di.providers.WalletInfoProvider
import org.tokend.template.base.logic.repository.base.SimpleMultipleItemsRepository
import org.tokend.template.extensions.toCompletable
import org.tokend.template.extensions.toSingle

class FavoritesRepository(
        private val apiProvider: ApiProvider,
        private val walletInfoProvider: WalletInfoProvider
) : SimpleMultipleItemsRepository<FavoriteEntry>() {
    override val itemsCache = FavoritesCache()

    override fun getItems(): Single<List<FavoriteEntry>> {
        val signedApi = apiProvider.getSignedApi()
                ?: return Single.error(IllegalStateException("No signed API instance found"))
        val accountId = walletInfoProvider.getWalletInfo()?.accountId
                ?: return Single.error(IllegalStateException("No wallet info found"))

        return signedApi.getFavorites(accountId)
                .toSingle()
                .map { it.data!! }
    }

    fun addToFavorites(entry: FavoriteEntry): Completable {
        val signedApi = apiProvider.getSignedApi()
                ?: return Completable.error(IllegalStateException("No signed API instance found"))
        val accountId = walletInfoProvider.getWalletInfo()?.accountId
                ?: return Completable.error(IllegalStateException("No wallet info found"))

        return signedApi.addToFavorites(accountId, entry)
                .toCompletable()
                .doOnSubscribe { isLoading = true }
                .doOnComplete {
                    isLoading = false
                    invalidate()
                }
                .andThen(updateDeferred())
    }

    fun removeFromFavorites(entryId: Long): Completable {
        val signedApi = apiProvider.getSignedApi()
                ?: return Completable.error(IllegalStateException("No signed API instance found"))
        val accountId = walletInfoProvider.getWalletInfo()?.accountId
                ?: return Completable.error(IllegalStateException("No wallet info found"))

        return signedApi.removeFromFavorites(accountId, entryId)
                .toCompletable()
                .doOnComplete {
                    itemsCache.merge(emptyList(), { it.id == entryId })
                    broadcast()
                }
                .doOnSubscribe { isLoading = true }
                .doOnTerminate { isLoading = false }
    }
}