package unibas.dmi.sdatadirect.utils

import android.content.Context
import android.util.Log
import com.fasterxml.jackson.databind.ObjectMapper
import unibas.dmi.sdatadirect.MainActivity
import unibas.dmi.sdatadirect.database.Feed
import unibas.dmi.sdatadirect.net.wifi.p2p.protocolUtils.SetSynchronization
import unibas.dmi.sdatadirect.peer.PeerViewModel
import unibas.dmi.sdatadirect.utils.PackageFactory.*
import unibas.dmi.sdatadirect.utils.PackageFactory.METHOD.DECLARE_FEED_KNOWN
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PackageInterpreter(
    val context: Context,
    val activity: MainActivity,
    val peerViewModel: PeerViewModel,
    val objectMapper: ObjectMapper = ObjectMapper()
){
    val TAG = "PackageInterpreter"
    fun interpret(msg: ByteArray, sender: String){
        val node = objectMapper.readTree(msg)
        val method: String = node.get("method").asText()
        Log.d(TAG, method)

        if (method == DECLARE_FEED_KNOWN.name){
            var feedKey = node.get("feedKey").asText()
            var subscribed = node.get("subscribed").asBoolean()
            SetSynchronization.receiveFeedUpdate(feedKey, subscribed, sender)
        }
        if (method == METHOD.INQUIRE_FEED_DETAILS.name){
            var feedKey = node.get("feedKey").asText()
            SetSynchronization.receiveFeedInquiry(feedKey, sender)
        }
        if (method == METHOD.ANSWER_FEED_QUERY.name){
            var feedKey = node.get("feedKey").asText()
            var subscribed = node.get("subscribed").asBoolean()
            var host = node.get("host").asText()
            var port = node.get("port").asText()
            var type = node.get("type").asText()
            SetSynchronization.receiveFeedInquiryAnswer(
                Feed(
                    key = feedKey,
                    type = type,
                    host = host,
                    port = port,
                    subscribed = false
            ), subscribed, sender
            )
        }
        if (method == METHOD.END_PHASE_ONE.name){
            SetSynchronization.receiveEndPhaseOne(sender)
        }

    }

    /**
     * for getting the package size
     */
    fun byteArrayToSize(byteBarray: ByteArray?): Int {
        return ByteBuffer.wrap(byteBarray).order(ByteOrder.LITTLE_ENDIAN).getInt()
    }
}