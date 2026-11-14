package com.brbrs.zaman.data.repo

import com.brbrs.zaman.data.carddav.CardDavContactsClient
import com.brbrs.zaman.data.carddav.VinciContact
import javax.inject.Inject
import javax.inject.Singleton

/**
 * A thin cache over CardDavContactsClient — contacts rarely change inside one Settings/event-edit
 * session, so there's no need for a Room table for v1; a fresh fetch on demand (or "Refresh" in
 * the picker) is enough, same as Calendars' "Add calendar" refresh pattern.
 */
@Singleton
class ContactsRepository @Inject constructor(
    private val client: CardDavContactsClient,
) {
    private var cache: List<VinciContact>? = null

    suspend fun contacts(forceRefresh: Boolean = false): List<VinciContact> {
        if (forceRefresh || cache == null) {
            cache = runCatching { client.fetchContacts() }.getOrDefault(emptyList())
        }
        return cache.orEmpty()
    }

    /**
     * A contact's photo, fetched and cached separately from the bulk [contacts] list — see
     * CardDavContactsClient.fetchContactsFrom's doc comment on why the bulk list itself never
     * carries photo data. Keyed by uid; `null` is cached too (a contact confirmed to have no
     * photo), so a chip that's already checked doesn't re-request on every recomposition.
     */
    private val photoCache = mutableMapOf<String, VinciContact?>()

    suspend fun photoFor(uid: String): VinciContact? {
        if (photoCache.containsKey(uid)) return photoCache[uid]
        val result = runCatching { client.fetchContactPhoto(uid) }.getOrNull()
        photoCache[uid] = result
        return result
    }
}
