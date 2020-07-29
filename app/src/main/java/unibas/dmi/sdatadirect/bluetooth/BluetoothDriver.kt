package unibas.dmi.sdatadirect.bluetooth

import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Message
import android.util.Log
import android.widget.*
import unibas.dmi.sdatadirect.MainActivity
import unibas.dmi.sdatadirect.crypto.CryptoHandler
import unibas.dmi.sdatadirect.database.Peer
import unibas.dmi.sdatadirect.peer.PeerViewModel
import unibas.dmi.sdatadirect.ui.BluetoothDeviceListAdapter
import unibas.dmi.sdatadirect.utils.QRCode
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.lang.Exception
import java.nio.charset.Charset
import java.util.*
import kotlin.collections.ArrayList
import kotlin.system.exitProcess

/**
 * BluetoothDriver contains all parameters and functions that helps to scan, pair and connect to
 * other bluetooth devices.
 *
 * Remark: If you like to work with Bluetooth Low Energy technology, consider the class BLEDriver
 *
 * Reference: https://developer.android.com/guide/topics/connectivity/bluetooth
 */
class BluetoothDriver(
    val activity: MainActivity,
    val handler: Handler,
    val qrCode: QRCode,
    val cryptoHandler: CryptoHandler,
    val peerViewModel: PeerViewModel) {

    private val TAG: String = "BluetoothDriver"

    val bluetoothAdapter: BluetoothAdapter? = BluetoothAdapter.getDefaultAdapter()
    val REQUEST_ENABLE_BT: Int = 1

    private val NAME: String = "sDaTaDirect"
    private val MY_UUID: UUID = UUID.fromString("6696ce56-49ac-459e-b4cf-502247bd8f21") // generated on the web

    private val MESSAGE_READ: Int = 0
    private val MESSAGE_WRITE: Int = 1
    private val MESSAGE_TOAST: Int = 2

    var acceptThread: AcceptThread? = null  // Server
    var connectThread: ConnectThread? = null    // Client
    var connectedThread: ConnectedThread? = null

    val receiver: BluetoothBroadcastReceiver = BluetoothBroadcastReceiver(this, activity)

    val devices: ArrayList<BluetoothDevice?> = ArrayList()
    //lateinit var adapter: ArrayAdapter<BluetoothDevice?>
    lateinit var bluetoothDeviceListAdapter: BluetoothDeviceListAdapter

    lateinit var progressBar: ProgressBar

    var scannedContent: String = ""

    fun checkForBluetoothAdapter(): Boolean {
        if (bluetoothAdapter == null) {
            val builder = AlertDialog.Builder(activity)
            builder.setTitle("No Bluetooth Adapter")
            builder.setMessage("No bluetooth adapter could be found on this device. This app" +
                    "cannot be further deployed.")
            builder.setNeutralButton("Close app") {_,_ ->
                activity.finish()
                exitProcess(0)
            }

            val dialog: AlertDialog = builder.create()
            dialog.show()

            return false
        } else {
            return true
        }
    }

    fun removePairs(device: BluetoothDevice?) {
        try {
            device!!::class.java.getMethod("removeBond").invoke(device)
        } catch (e: Exception) {
            Log.d(TAG, "Removing bond has been failed ${e.message}")
        }

    }

    fun discoverable(enabled: Boolean) {
        if (enabled) {
            Log.d(TAG, "Making device discoverable")
            val discoverableIntent: Intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 100)
            activity.startActivity(discoverableIntent)
        } else {
            Log.d(TAG, "Making device not discoverable anymore")
            val discoverableIntent: Intent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE)
            discoverableIntent.putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 1)
            activity.startActivity(discoverableIntent)
        }
    }

    fun startDiscovery() {
        Log.d(TAG, "Looking for unpaired devices.");

        //bluetoothAdapter?.cancelDiscovery()
        bluetoothAdapter?.startDiscovery()
    }

    /**
     * Start AcceptThread (server) to begin a session in listening for incoming connections.
     */
    fun startServer() {
        Log.d(TAG, "Starting server thread and waiting for incoming connections ...")
        //connectThread?.cancel()
        //connectedThread?.cancel()
        acceptThread = AcceptThread(this)
        acceptThread?.start()
    }

    /**
     * Start ConnectThread (client) to attempt a connection with a server device.
     */
    fun connect(device: BluetoothDevice?) {
        //connectThread?.cancel()
        //connectedThread?.cancel()
        Log.d(TAG, "Connecting Bluetooth ...")

        val message: Message = Message.obtain()
        message.what = activity.STATE_CONNECTING
        handler.sendMessage(message)

        connectThread = ConnectThread(this, device)
        connectThread?.start()
    }

    fun stop() {
        connectThread?.cancel()
        connectedThread?.cancel()
        acceptThread?.cancel()
        Log.d(TAG, "Bluetooth Driver stopped")
    }

    /**
     * A thread for the server component that accepts incoming connections. The component start
     * listening for connection requests by calling accept(). The connections is accepted if the
     * connection request contains a UUID that matches the one registered with this listening
     * server socket. After accepting the request, it returns a connected BluetoothSocket.
     *
     * Remark:
     * - accept() is a blocking call. Any work involving BluetoothServerSocket should be done in a separate thread.
     * - close() releases server socket and all its resources, but does not close the connected BluetoothSocket.
     */
    inner class AcceptThread(val bluetoothDriver: BluetoothDriver): Thread() {
        private val mmServerSocket: BluetoothServerSocket? by lazy(LazyThreadSafetyMode.NONE) {
            bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(NAME, MY_UUID)
        }
        var socket: BluetoothSocket? = null
        private val mmBuffer: ByteArray = ByteArray(1024)

        //private val mmServerSocket: BluetoothServerSocket? = bluetoothAdapter?.listenUsingInsecureRfcommWithServiceRecord(NAME, MY_UUID)

        override fun run() {
            // Keep listening until exception occurs or a socket is returned.

            var shouldLoop = true
            //var socket: BluetoothSocket? = null

            while (shouldLoop) {

                try {
                    socket = mmServerSocket?.accept()
                    /*Log.d(TAG, socket?.remoteDevice?.name)
                    val message: Message = Message.obtain()
                    message.what = activity.STATE_CONNECTING
                    handler.sendMessage(message)*/
                } catch (e: IOException) {
                    /*Log.e(TAG, "Socket's accept() method failed", e)
                    val message: Message = Message.obtain()
                    message.what = activity.STATE_CONNECTION_FAILED
                    handler.sendMessage(message)*/
                    shouldLoop = false
                }

                socket?.also {
                    val message: Message = Message.obtain()
                    message.what = activity.STATE_CONNECTED
                    handler.sendMessage(message)
                    activity.wifiP2pDriver.wantsToBeClient = false

                    val peer1 = peerViewModel.getPeerByBluetoothAddress(it.remoteDevice.address)


                    if (peer1 == null) {

                        val newPeer = Peer(
                            name = it.remoteDevice.name,
                            bluetooth_mac_address = it.remoteDevice.address,
                            wifi_mac_address = qrCode.scannedContent?.split("#")?.get(0),
                            shared_key = cryptoHandler.getSecretKeyEncoded(cryptoHandler.sharedAESKey!!),
                            public_key = cryptoHandler.getPublicKeyEncoded(cryptoHandler.publicRSAKey!!),
                            private_key = cryptoHandler.getPrivateKeyEncoded(cryptoHandler.privateRSAKey!!),
                            foreign_public_key = qrCode.scannedContent?.split("#")?.get(2)
                        )

                        Log.d(TAG, "DeviceName: ${it.remoteDevice.name}")
                        Log.d(TAG, "BluetoothAddres: ${it.remoteDevice.address}")
                        Log.d(TAG, "WiFiAddress: ${qrCode.scannedContent?.split("#")?.get(0)}")
                        Log.d(TAG, "SharedKey: ${cryptoHandler.getSecretKeyEncoded(cryptoHandler.sharedAESKey!!)}")
                        Log.d(TAG, "PublicKey: ${cryptoHandler.getPublicKeyEncoded(cryptoHandler.publicRSAKey!!)}")
                        Log.d(TAG, "PrivateKey: ${cryptoHandler.getPrivateKeyEncoded(cryptoHandler.privateRSAKey!!)}")
                        Log.d(TAG, "ForeignKey: ${qrCode.scannedContent?.split("#")?.get(2)}")

                        peerViewModel.insert(newPeer)
                        val message: Message = Message.obtain()
                        message.what = activity.PEER_SAVED
                        handler.sendMessage(message)
                    }

                    // Retrieve it again after saving
                    val peer2 = peerViewModel.getPeerByBluetoothAddress(it.remoteDevice.address)

                    val sharedKey = cryptoHandler.getSecretKeyDecoded(peer2?.shared_key!!)
                    Log.d(TAG, "Server sharedkey1: ${peer2.shared_key}")
                    val signature = cryptoHandler.createSignature(sharedKey.encoded, peer2.private_key)

                    write(signature)


                    while (true) {
                        // Read from the InputStream.
                        try {
                            socket!!.inputStream.read(mmBuffer)
                        } catch (e: IOException) {
                            Log.e(TAG, e.message)
                            break
                        }

                        /*  VS => "Verification Successful"
                        *   VNS => "Verification Not Successful"
                        * */
                        if (String(mmBuffer, Charset.defaultCharset()) == "VS") {
                            Toast.makeText(activity, "Verification successful", Toast.LENGTH_SHORT)
                            bluetoothDriver.stop()
                            activity.wifiP2pDriver.discoverPeers()
                        } else if (String(mmBuffer, Charset.defaultCharset()) == "VNS") {
                            val message: Message = Message.obtain()
                            message.what = activity.VERIFICATION_FAILED
                            handler.sendMessage(message)
                            break
                        } else {
                            val remotePeer =
                                peerViewModel.getPeerByBluetoothAddress(it.remoteDevice.address)

                            val sharedKey2 =
                                cryptoHandler.getSecretKeyDecoded(remotePeer?.shared_key!!)
                            Log.d(TAG, "Server sharedkey2: ${remotePeer.shared_key}")
                            Log.d(TAG, "Server publicKey: ${remotePeer.public_key}")
                            Log.d(TAG, "Server foreignKey: ${remotePeer.foreign_public_key}")

                            val verification = cryptoHandler.verifySignature(
                                mmBuffer,
                                sharedKey2.encoded,
                                remotePeer.foreign_public_key!!
                            )

                            if (verification) {
                                Toast.makeText(activity, "Verification successful", Toast.LENGTH_SHORT)
                                write("VS".toByteArray())
                            } else {
                                write("VNS".toByteArray())
                            }
                        }
                    }
                    mmServerSocket?.close()
                    shouldLoop = false
                }

            }
        }


        fun write(bytes: ByteArray) {
            try {
                socket?.outputStream?.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)

                // Send a failure message back to the activity.
                val writeErrorMsg = handler.obtainMessage(MESSAGE_TOAST)
                val bundle = Bundle().apply {
                    putString("toast", "Couldn't send data to the other device")
                }
                writeErrorMsg.data = bundle
                handler.sendMessage(writeErrorMsg)
                //return
            }
        }

        // Closes the connect socket and causes the thread to finish.
        fun cancel() {
            try {
                mmServerSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the connect socket", e)
            }
        }
    }

    /**
     * A thread for the client component, that issues a connection to a desired bluetooth device.
     * To establish a connection a BluetoothSocket from the device must be acquired. The client calls
     * the connect() method. The system will perform a SDP lookup to find the remote device with the
     * matching UUID. After the device accepted the connection, it shared the RFCOMM channel.
     *
     * Remark: connect() is a blocking call. Time out after about 12 seconds.
     */
    inner class ConnectThread(val bluetoothDriver: BluetoothDriver, val device: BluetoothDevice?) : Thread() {

        private val mmSocket: BluetoothSocket? by lazy(LazyThreadSafetyMode.NONE) {
            device?.createRfcommSocketToServiceRecord(MY_UUID)
        }
        private val mmBuffer: ByteArray = ByteArray(1024)

        //private val mmSocket: BluetoothSocket? = device?.createRfcommSocketToServiceRecord(MY_UUID)

        override fun run() {
            // Cancel discovery because it otherwise slows down the connection.
            bluetoothAdapter?.cancelDiscovery()
            mmSocket?.use { socket ->

                //acceptThread?.cancel()
                socket.connect()
                val message: Message = Message.obtain()
                message.what = activity.STATE_CONNECTED
                handler.sendMessage(message)
                Log.d(TAG, "run: ConnectThread connected.")

                val peer = peerViewModel.getPeerByBluetoothAddress(device?.address!!)

                activity.wifiP2pDriver.wantsToBeClient = true
                activity.wifiP2pDriver.deviceWantsToConnectTo = peer?.wifi_mac_address!!


                val sharedKey = cryptoHandler.getSecretKeyDecoded(peer.shared_key!!)
                Log.d(TAG, "Client sharedkey1: ${peer.shared_key}")
                val signature = cryptoHandler.createSignature(sharedKey.encoded, peer.private_key)

                write(signature)


                while (true) {
                    // Read from the InputStream.
                    try {
                        mmSocket!!.inputStream.read(mmBuffer)
                    } catch (e: IOException) {
                        Log.e(TAG, e.message)
                        break
                    }

                    /*  VS => "Verification Successful"
                    *   VNS => "Verification Not Successful"
                    * */
                    if (String(mmBuffer, Charset.defaultCharset()) == "VS") {
                        Toast.makeText(activity, "Verification successful", Toast.LENGTH_SHORT)
                        bluetoothDriver.stop()
                        activity.wifiP2pDriver.discoverPeers()
                    } else if (String(mmBuffer, Charset.defaultCharset()) == "VNS") {
                        val message: Message = Message.obtain()
                        message.what = activity.VERIFICATION_FAILED
                        handler.sendMessage(message)
                        break
                    } else {
                        val remotePeer =
                            peerViewModel.getPeerByBluetoothAddress(device.address)

                        val sharedKey2 =
                            cryptoHandler.getSecretKeyDecoded(remotePeer?.shared_key!!)
                        Log.d(TAG, "Client sharedkey2: ${remotePeer.shared_key}")
                        Log.d(TAG, "Client publicKey: ${remotePeer.public_key}")
                        Log.d(TAG, "Client foreignKey: ${remotePeer.foreign_public_key}")

                        val verification = cryptoHandler.verifySignature(
                            mmBuffer,
                            sharedKey2.encoded,
                            remotePeer.foreign_public_key!!
                        )

                        if (verification) {
                            Toast.makeText(activity, "Verification successful", Toast.LENGTH_SHORT)
                            write("VS".toByteArray())
                        } else {
                            write("VNS".toByteArray())
                        }
                    }
                }
                socket.close()
            }

        }

        fun write(bytes: ByteArray) {
            try {
                mmSocket?.outputStream?.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)

                // Send a failure message back to the activity.
                val writeErrorMsg = handler.obtainMessage(MESSAGE_TOAST)
                val bundle = Bundle().apply {
                    putString("toast", "Couldn't send data to the other device")
                }
                writeErrorMsg.data = bundle
                handler.sendMessage(writeErrorMsg)
                //return
            }
        }

        // Closes the client socket and causes the thread to finish.
        fun cancel() {
            try {
                mmSocket?.close()
            } catch (e: IOException) {
                Log.e(TAG, "Could not close the client socket", e)
            }
        }
    }

    /**
     * ConnectedThread which is responsible for maintaining connection and sharing data.
     */
    inner class ConnectedThread(
        val bluetoothDriver: BluetoothDriver,
        val mmSocket: BluetoothSocket,
        val remoteDeviceAddress: String?) : Thread() {

        private val mmInStream: InputStream = mmSocket.inputStream
        private val mmOutStream: OutputStream = mmSocket.outputStream
        private val mmBuffer: ByteArray = ByteArray(1024) // mmBuffer store for the stream

        override fun run() {
            var numBytes: Int = 0 // bytes returned from read()

            // Keep listening to the InputStream until an exception occurs.
            while (true) {
                // Read from the InputStream.
                try {
                    numBytes = mmInStream.read(mmBuffer)
                } catch (e: IOException) {
                    Log.e(TAG, e.message)
                    break
                }

                /*  VS => "Verification Successful"
                *   VNS => "Verification Not Successful"
                * */
                if (String(mmBuffer, Charset.defaultCharset()) == "VS") {
                    Toast.makeText(activity, "Verification successful", Toast.LENGTH_SHORT)
                    bluetoothDriver.stop()
                    activity.wifiP2pDriver.discoverPeers()
                } else if (String(mmBuffer, Charset.defaultCharset()) == "VNS"){
                    val message: Message = Message.obtain()
                    message.what = activity.VERIFICATION_FAILED
                } else {
                    val remotePeer =
                        peerViewModel.getPeerByBluetoothAddress(remoteDeviceAddress!!)

                    val sharedKey =
                        activity.cryptoHandler.getSecretKeyDecoded(remotePeer?.shared_key!!)

                    val verification = activity.cryptoHandler.verifySignature(
                        mmBuffer,
                        sharedKey.encoded,
                        remotePeer.foreign_public_key!!
                    )

                    if (verification) {
                        Toast.makeText(activity, "Verification successful", Toast.LENGTH_SHORT)
                        write("VS".toByteArray())
                    } else {
                        write("VNS".toByteArray())
                        val message: Message = Message.obtain()
                        message.what = activity.VERIFICATION_FAILED
                        handler.sendMessage(message)
                    }
                }



                /*val readMsg = activity.handler.obtainMessage(activity.STATE_MESSAGE_RECEIVED, numBytes,
                    -1, mmBuffer)
                readMsg.sendToTarget()*/

            }
        }

        // Call this from the main activity to send data to the remote device.
        fun write(bytes: ByteArray) {
            try {
                mmOutStream.write(bytes)
            } catch (e: IOException) {
                Log.e(TAG, "Error occurred when sending data", e)

                // Send a failure message back to the activity.
                val writeErrorMsg = handler.obtainMessage(MESSAGE_TOAST)
                val bundle = Bundle().apply {
                    putString("toast", "Couldn't send data to the other device")
                }
                writeErrorMsg.data = bundle
                handler.sendMessage(writeErrorMsg)
                //return
            }


            // Share the sent message with the UI activity.
            /*val writtenMsg = handler.obtainMessage(
                MESSAGE_WRITE, -1, -1, mmBuffer)
            writtenMsg.sendToTarget()*/
        }

        // Call this method from the main activity to shut down the connection.
        fun cancel() {
            try {
                mmSocket.close()
            } catch (e: IOException) {
            }
        }
    }

}