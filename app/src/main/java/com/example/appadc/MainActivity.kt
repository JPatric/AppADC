package com.example.appadc

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageButton
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var chart: LineChart
    private lateinit var btnConnectBluetooth: ImageButton
    private lateinit var btnLoadData: Button
    private lateinit var btnStopSending: Button
    private lateinit var btnSaveData: ImageButton
    private lateinit var btnShowInfo: ImageButton
    private lateinit var gainSpinner: Spinner
    private var isReceivingData = false
    private lateinit var bluetoothSocket: BluetoothSocket
    private lateinit var dataReceiverThread: Thread
    private val REQUEST_PERMISSION_CODE = 101
    private val REQUEST_EXPORT_CODE = 102
    private lateinit var exportDataLauncher: ActivityResultLauncher<Intent>

    private val BLUETOOTH_PERMISSION_CODE = 1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initializing UI elements
        chart = findViewById(R.id.chart)
        btnConnectBluetooth = findViewById(R.id.btnConnectBluetooth)
        btnLoadData = findViewById(R.id.btnLoadData)
        gainSpinner = findViewById(R.id.spinnerGain)
        btnStopSending = findViewById(R.id.btnStopSending)
        btnSaveData = findViewById(R.id.btnSaveData)
        btnShowInfo = findViewById(R.id.btnShowInfo)
        btnSaveData.isEnabled = false

        // Activity result launcher for exporting data
        exportDataLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                Toast.makeText(this, "Data exported successfully", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this, "Error exporting data", Toast.LENGTH_SHORT).show()
            }
        }

        // Bluetooth connection button click listener
        btnConnectBluetooth.setOnClickListener {
            requestBluetoothPermission()
        }

        // Configure the Spinner
        val gainOptions = arrayOf("GAIN", "0", "10","100","1000") // Modify according to your options
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, gainOptions)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        gainSpinner.adapter = adapter

        // Load Data button click listener
        btnLoadData.setOnClickListener {
            val selectedOption = gainSpinner.selectedItem.toString()
            if (selectedOption != "GAIN") {
                // Call your function to send data to Arduino
                sendToArduino(selectedOption)
                startReceivingData()
                btnStopSending.isEnabled = true
                btnSaveData.isEnabled = true
            } else {
                Toast.makeText(this, "Select a valid gain option", Toast.LENGTH_SHORT).show()
                btnStopSending.isEnabled = false
                btnSaveData.isEnabled = false
            }
        }

        // Stop Sending button click listener
        btnStopSending.setOnClickListener {
            stopReceivingData()
        }

        // Save Data button click listener
        btnSaveData.setOnClickListener {

            saveData()
        }

        // Show Info button click listener
        btnShowInfo.setOnClickListener {
            showAppInfoDialog()
        }
    }

    // Function to request Bluetooth permission
    private fun requestBluetoothPermission() {
        // Check if Bluetooth permission is not granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) != PackageManager.PERMISSION_GRANTED) {
            // Request Bluetooth permission
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH), BLUETOOTH_PERMISSION_CODE)
        } else {
            // Connect to any Bluetooth device
            connectToAnyBluetoothDevice()
        }
    }

    // Function to connect to any Bluetooth device
    private fun connectToAnyBluetoothDevice() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
            val pairedDevices: Set<BluetoothDevice>? = BluetoothAdapter.getDefaultAdapter().bondedDevices
            pairedDevices?.let {
                val deviceList = ArrayList(it)
                val deviceNames = deviceList.map { device -> device.name }.toTypedArray()

                val builder = AlertDialog.Builder(this)
                builder.setTitle("Select a Bluetooth Device")
                builder.setItems(deviceNames) { dialog, which ->
                    val selectedDevice = deviceList[which]
                    connectToDevice(selectedDevice)
                }
                builder.show()
            }
        } else {
            // Handle the case where Bluetooth permission is not granted
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH), BLUETOOTH_PERMISSION_CODE)
        }
    }

    // Function to connect to a specific Bluetooth device
    private fun connectToDevice(device: BluetoothDevice) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH) == PackageManager.PERMISSION_GRANTED) {
            val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
            try {
                bluetoothSocket = device.createRfcommSocketToServiceRecord(uuid)
                bluetoothSocket.connect()
                btnLoadData.isEnabled = true
                gainSpinner.isEnabled = true
                btnConnectBluetooth.setImageResource(R.drawable.bluetooth__c)
                btnLoadData.backgroundTintList = getColorStateList(R.color.green)
            } catch (e: IOException) {
                e.printStackTrace()
                gainSpinner.isEnabled = false
                btnLoadData.isEnabled = false
            }
        } else {
            // Handle the case where Bluetooth permission is not granted
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.BLUETOOTH), BLUETOOTH_PERMISSION_CODE)
        }
    }

    // Function to start receiving data from Bluetooth device
    private fun startReceivingData() {
        val inputStream: InputStream = bluetoothSocket.inputStream
        val buffer = ByteArray(1024)
        var bytesRead: Int

        val entries = ArrayList<Entry>()
        val dataSet = LineDataSet(entries, "Data ADC")
        val lineData = LineData(dataSet)
        chart.data = lineData

        isReceivingData = true
        dataReceiverThread = Thread {
            while (isReceivingData) {
                try {
                    bytesRead = inputStream.read(buffer)
                    val data = String(buffer, 0, bytesRead).trim()
                    val value = data.toFloatOrNull()
                    if (value != null) {
                        runOnUiThread {
                            val entry = Entry(dataSet.entryCount.toFloat(), value)
                            dataSet.addEntry(entry)
                            lineData.notifyDataChanged()
                            chart.notifyDataSetChanged()
                            chart.invalidate()
                        }
                    }
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        }
        dataReceiverThread.start()
        btnStopSending.isEnabled = true
        btnStopSending.backgroundTintList = getColorStateList(R.color.red)
        btnSaveData.isEnabled = true
    }

    // Function to stop receiving data
    private fun stopReceivingData() {
        isReceivingData = false
        try {
            dataReceiverThread.join()
        } catch (e: InterruptedException) {
            e.printStackTrace()
        }
        btnStopSending.isEnabled = false
    }

    // Function to save received data to a file
    private fun saveData() {
        val dataToExport = StringBuilder()

        val dataSet = chart.data.dataSets.firstOrNull() as? LineDataSet
        dataSet?.let {
            val entries = dataSet.values

            for (entry in entries) {
                dataToExport.append("${entry.x},${entry.y}\n")
            }

            val fileName = "data.txt"
            val mimeType = "text/plain" // MIME type for plain text files

            // Check if WRITE_EXTERNAL_STORAGE permission is not granted
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                // Request WRITE_EXTERNAL_STORAGE permission
                ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), REQUEST_PERMISSION_CODE)
            } else {
                // Save data to a file
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
                }

                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Files.getContentUri("external"), values)
                uri?.let {
                    resolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.write(dataToExport.toString().toByteArray())
                        outputStream.close()
                        Toast.makeText(this, "Data exported successfully", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    // Function to send data to Arduino
    private fun sendToArduino(option: String) {
        try {
            // Get the outputStream from bluetoothSocket
            val outputStream = bluetoothSocket.outputStream

            // Convert the option to bytes
            val optionBytes = option.toByteArray()

            // Send the option to Arduino
            outputStream.write(optionBytes)

            // Display a log message or Toast to indicate that the option was sent successfully
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Option sent to Arduino: $option", Toast.LENGTH_SHORT).show()
            }

        } catch (e: IOException) {
            // Handle IO errors, for example, if the Bluetooth connection is closed
            e.printStackTrace()

            // Display a log message or Toast to indicate that there was an error sending the option
            runOnUiThread {
                Toast.makeText(this@MainActivity, "Error sending option to Arduino", Toast.LENGTH_SHORT).show()
            }
        }
    }

    // Function to show application information dialog
    private fun showAppInfoDialog() {
        val infoMessage = getString(R.string.app_info)

        // Create a dialog
        val builder = AlertDialog.Builder(this)
        builder.setTitle("Application Information")
            .setMessage(infoMessage)
            .setPositiveButton("Close") { dialog, _ ->
                dialog.dismiss()
            }

        // Show the dialog
        builder.create().show()
    }

    override fun onDestroy() {
        super.onDestroy()
        // Close the Bluetooth socket when the activity is destroyed
        isReceivingData = false
        try {
            bluetoothSocket.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}


