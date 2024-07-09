package com.example.test2

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var imageView: ImageView
    private lateinit var textViewPrediction: TextView
    private val client = OkHttpClient()

    private lateinit var takePicture: ActivityResultLauncher<Intent>
    private lateinit var choosePicture: ActivityResultLauncher<Intent>

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_PERMISSION_CAMERA = 100
        private const val REQUEST_PERMISSION_STORAGE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)  // Référence au fichier de mise en page main_activity.xml

        val buttonTakePhoto: Button = findViewById(R.id.button_take_photo)
        imageView = findViewById(R.id.image_view)
        textViewPrediction = findViewById(R.id.text_view_prediction)

        setupActivityResultLaunchers()

        buttonTakePhoto.setOnClickListener {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                requestPermission(Manifest.permission.CAMERA, REQUEST_PERMISSION_CAMERA)
            } else {
                dispatchTakePictureIntent()
            }
        }
    }

    private fun setupActivityResultLaunchers() {
        takePicture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val imageBitmap = result.data?.extras?.get("data") as Bitmap
                Log.d(TAG, "Image taken with camera")
                imageView.setImageBitmap(imageBitmap)
                sendImageToServer(imageBitmap)
            } else {
                Log.d(TAG, "Failed to take image with camera")
            }
        }

        choosePicture = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val imageUri: Uri? = result.data?.data
                if (imageUri != null) {
                    val imageBitmap = if (Build.VERSION.SDK_INT < 28) {
                        MediaStore.Images.Media.getBitmap(contentResolver, imageUri)
                    } else {
                        val source = ImageDecoder.createSource(contentResolver, imageUri)
                        ImageDecoder.decodeBitmap(source)
                    }
                    Log.d(TAG, "Image chosen from gallery")
                    imageView.setImageBitmap(imageBitmap)
                    sendImageToServer(imageBitmap)
                } else {
                    Log.d(TAG, "Failed to get image URI from gallery")
                }
            } else {
                Log.d(TAG, "Failed to choose image from gallery")
            }
        }
    }

    private fun requestPermission(permission: String, requestCode: Int) {
        Log.d(TAG, "Requesting permission: $permission")
        ActivityCompat.requestPermissions(this, arrayOf(permission), requestCode)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_PERMISSION_CAMERA -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Camera permission granted")
                    dispatchTakePictureIntent()
                } else {
                    Log.d(TAG, "Camera permission denied")
                    Toast.makeText(this, "Camera permission denied", Toast.LENGTH_SHORT).show()
                }
            }
            REQUEST_PERMISSION_STORAGE -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    Log.d(TAG, "Storage permission granted")
                    dispatchChoosePictureIntent()
                } else {
                    Log.d(TAG, "Storage permission denied")
                    Toast.makeText(this, "Storage permission denied", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun dispatchTakePictureIntent() {
        val takePictureIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        Log.d(TAG, "Dispatching take picture intent")
        takePicture.launch(takePictureIntent)
    }

    private fun dispatchChoosePictureIntent() {
        val choosePictureIntent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        Log.d(TAG, "Dispatching choose picture intent")
        choosePicture.launch(choosePictureIntent)
    }

    private fun sendImageToServer(bitmap: Bitmap) {
        Log.d(TAG, "Preparing image to send to server")
        val byteArrayOutputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 100, byteArrayOutputStream)
        val byteArray = byteArrayOutputStream.toByteArray()

        val file = File(cacheDir, "image.jpg")
        try {
            val fileOutputStream = FileOutputStream(file)
            fileOutputStream.write(byteArray)
            fileOutputStream.flush()
            fileOutputStream.close()
            Log.d(TAG, "Image file created successfully")
        } catch (e: IOException) {
            e.printStackTrace()
            Log.d(TAG, "Failed to create image file: ${e.message}")
        }

        val requestBody = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart("file", file.name, RequestBody.create("image/jpeg".toMediaTypeOrNull(), file))
            .build()

        val request = Request.Builder()
            .url("http://192.168.88.60:5000/predict")  // Utilisez l'IP correcte du serveur
            .post(requestBody)
            .build()

        Log.d(TAG, "Sending image to server")
        client.newCall(request).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                e.printStackTrace()
                Log.d(TAG, "Failed to get response: ${e.message}")
                runOnUiThread {
                    Toast.makeText(this@MainActivity, "Failed to get response: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }

            override fun onResponse(call: Call, response: Response) {
                response.use {
                    if (!it.isSuccessful) {
                        Log.d(TAG, "Failed to get prediction: ${it.message}")
                        runOnUiThread {
                            Toast.makeText(this@MainActivity, "Failed to get prediction: ${it.message}", Toast.LENGTH_SHORT).show()
                        }
                        return
                    }

                    val responseData = it.body?.string() ?: "No response"
                    Log.d(TAG, "Received response from server: $responseData")
                    runOnUiThread {
                        textViewPrediction.text = "Prediction: $responseData"
                    }
                }
            }
        })
    }
}
