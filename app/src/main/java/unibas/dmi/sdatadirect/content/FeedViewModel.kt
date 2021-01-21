package unibas.dmi.sdatadirect.content

import unibas.dmi.sdatadirect.ui.FeedListAdapter
import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import unibas.dmi.sdatadirect.database.AppDatabase
import unibas.dmi.sdatadirect.database.Feed

class FeedViewModel(application: Application): AndroidViewModel(application) {

    private val repository: FeedRepository
    val allFeeds: LiveData<List<Feed>>

    init {
        val feedDao = AppDatabase.getDatabase(application, viewModelScope).feedsDao()
        repository = FeedRepository(feedDao)
        allFeeds = repository.allFeeds
    }

    /**
     * Launching a new coroutine to insert the data in a non-blocking way
     */
    fun insert(feed: Feed) =viewModelScope.launch(Dispatchers.IO) {
        repository.insert(feed)
    }


    fun subscribe(feed_key: String){
        repository.subscribe(feed_key)
    }

    fun unsubscribe(feed_key: String){
        repository.unsubscribe(feed_key)
    }


}