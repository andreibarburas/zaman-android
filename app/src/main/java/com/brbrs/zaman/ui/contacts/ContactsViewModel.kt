package com.brbrs.zaman.ui.contacts

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.brbrs.zaman.data.carddav.VinciContact
import com.brbrs.zaman.data.repo.ContactsRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ContactsViewModel @Inject constructor(
    private val repository: ContactsRepository,
) : ViewModel() {

    private val _contacts = MutableStateFlow<List<VinciContact>>(emptyList())
    val contacts: StateFlow<List<VinciContact>> = _contacts

    private var loaded = false

    /** Safe to call every time the picker opens — only hits the network once per session unless forced. */
    fun load(forceRefresh: Boolean = false) {
        if (loaded && !forceRefresh) return
        loaded = true
        viewModelScope.launch {
            _contacts.value = repository.contacts(forceRefresh)
        }
    }

    /**
     * uid -> the SAME contact with its photo fields populated, once fetched — the bulk [contacts]
     * list never carries photo data (see ContactsRepository.photoFor's doc comment), so an
     * attendee chip reads this map, falling back to the plain (photo-less) contact from
     * [contacts] while — or if — its own fetch hasn't resolved yet.
     */
    private val _photos = MutableStateFlow<Map<String, VinciContact>>(emptyMap())
    val photos: StateFlow<Map<String, VinciContact>> = _photos

    private val photoRequestsInFlight = mutableSetOf<String>()

    /** Call once per distinct attendee actually being displayed (e.g. from a LaunchedEffect keyed
     *  on that contact's uid) — never for the whole contacts list, that's the exact cost this is
     *  avoiding. A no-op if already fetched or already in flight for this uid this session. */
    fun ensurePhotoLoaded(uid: String) {
        if (_photos.value.containsKey(uid) || !photoRequestsInFlight.add(uid)) return
        viewModelScope.launch {
            repository.photoFor(uid)?.let { withPhoto ->
                _photos.value = _photos.value + (uid to withPhoto)
            }
        }
    }
}
