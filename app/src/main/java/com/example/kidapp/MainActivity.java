package com.example.kidapp;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.Camera;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.AsyncTask;

import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.speech.tts.TextToSpeech;
import android.util.Log;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import com.google.android.gms.tasks.OnFailureListener;
import com.google.android.gms.tasks.OnSuccessListener;
import com.google.android.gms.tasks.Task;
import com.google.firebase.ml.vision.FirebaseVision;
import com.google.firebase.ml.vision.common.FirebaseVisionImage;
import com.google.firebase.ml.vision.common.FirebaseVisionImageMetadata;
import com.google.firebase.ml.vision.text.FirebaseVisionCloudTextRecognizerOptions;
import com.google.firebase.ml.vision.text.FirebaseVisionText;
import com.google.firebase.ml.vision.text.FirebaseVisionTextRecognizer;
import com.google.firebase.ml.vision.text.RecognizedLanguage;
import com.googlecode.tesseract.android.TessBaseAPI;


import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.appcompat.app.AppCompatActivity;

import static android.speech.tts.TextToSpeech.ERROR;


public class MainActivity extends AppCompatActivity{
    TessBaseAPI tessBaseAPI;

    private TextToSpeech tts_ko;
    private TextToSpeech tts_en;


    Button button;
    ImageView imageView;
    CameraSurfaceView surfaceView;
    TextView textView;
    TextView textView2;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        imageView = findViewById(R.id.imageView);
        surfaceView = findViewById(R.id.surfaceView);
        textView = findViewById(R.id.textView);
        textView2 = findViewById(R.id.textView2);
        button = findViewById(R.id.button);


        //Speech language select.

        tts_ko = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status != ERROR) {
                    tts_ko.setLanguage(Locale.KOREAN);
                }
            }
        });
        tts_en = new TextToSpeech(this, new TextToSpeech.OnInitListener() {
            @Override
            public void onInit(int status) {
                if(status != ERROR) {
                    tts_en.setLanguage(Locale.KOREAN);
                }
            }
        });


        //button event
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                capture();
            }
        });
    }


    // Translation Handler
    @SuppressLint("HandlerLeak")
    Handler papago_handler = new Handler(){
        @Override
        public void handleMessage(Message msg) {
            Bundle bundle = msg.getData();
            String resultWord = bundle.getString("resultWord");
            textView2.setText(resultWord);
            tts_ko.speak(resultWord,TextToSpeech.QUEUE_FLUSH, null);

            //Toast.makeText(getApplicationContext(),resultWord,Toast.LENGTH_SHORT).show();
        }
    };


    //Capture event
    private void capture()
    {
        surfaceView.capture(new Camera.PictureCallback() {
            @Override
            public void onPictureTaken(byte[] bytes, Camera camera) {
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = 8;

                Bitmap bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                bitmap = GetRotatedBitmap(bitmap, 90);

                imageView.setImageBitmap(bitmap);
                button.setEnabled(false);
                button.setText("텍스트 인식중...");

                FirebaseVisionImage image = FirebaseVisionImage.fromBitmap(bitmap);
                FirebaseVisionTextRecognizer detector = FirebaseVision.getInstance()
                        .getCloudTextRecognizer();

                //Passing an image to the API
                Task<FirebaseVisionText> result =
                        detector.processImage(image)
                                .addOnSuccessListener(new OnSuccessListener<FirebaseVisionText>() {
                                    @Override
                                    public void onSuccess(final FirebaseVisionText firebaseVisionText) {

                                        Toast.makeText(MainActivity.this, "성공", Toast.LENGTH_LONG).show();
                                        processTextBlock(firebaseVisionText);
                                        tts_en.speak(firebaseVisionText.getText(),TextToSpeech.QUEUE_FLUSH, null);

                                        //English translation
                                        new Thread(){
                                            @Override
                                            public void run() {
                                                String word = TransformMinuscule(firebaseVisionText.getText());
                                                Menu_papago papago = new Menu_papago();
                                                String resultWord;

                                                resultWord= papago.getTranslation(word,"en","ko");

                                                Bundle papagoBundle = new Bundle();
                                                papagoBundle.putString("resultWord",resultWord);

                                                Message msg = papago_handler.obtainMessage();
                                                msg.setData(papagoBundle);
                                                papago_handler.sendMessage(msg);
                                            }
                                        }.start();
                                    }
                                })
                                .addOnFailureListener(
                                        new OnFailureListener() {
                                            @Override
                                            public void onFailure(@NonNull Exception e) {
                                                Toast.makeText(MainActivity.this, "실패", Toast.LENGTH_LONG).show();
                                            }
                                        });
                camera.startPreview();
            }
        });
    }


    //image rotation
    public synchronized static Bitmap GetRotatedBitmap(Bitmap bitmap, int degrees) {
        if (degrees != 0 && bitmap != null) {
            Matrix m = new Matrix();
            m.setRotate(degrees, (float) bitmap.getWidth() / 2, (float) bitmap.getHeight() / 2);
            try {
                Bitmap b2 = Bitmap.createBitmap(bitmap, 0, 0, bitmap.getWidth(), bitmap.getHeight(), m, true);
                if (bitmap != b2) {
                    bitmap = b2;
                }
            } catch (OutOfMemoryError ex) {
                ex.printStackTrace();
            }
        }
        return bitmap;
    }



    //Extract text from recognized text blocks

    private void processTextBlock(FirebaseVisionText result) {
        // [START mlkit_process_text_block]
        String resultText = result.getText();
        for (FirebaseVisionText.TextBlock block: result.getTextBlocks()) {
            String blockText = block.getText();
            Float blockConfidence = block.getConfidence();
            List<RecognizedLanguage> blockLanguages = block.getRecognizedLanguages();
            Point[] blockCornerPoints = block.getCornerPoints();
            Rect blockFrame = block.getBoundingBox();
            for (FirebaseVisionText.Line line: block.getLines()) {
                String lineText = line.getText();
                Float lineConfidence = line.getConfidence();
                List<RecognizedLanguage> lineLanguages = line.getRecognizedLanguages();
                Point[] lineCornerPoints = line.getCornerPoints();
                Rect lineFrame = line.getBoundingBox();
                for (FirebaseVisionText.Element element: line.getElements()) {
                    String elementText = element.getText();
                    Float elementConfidence = element.getConfidence();
                    List<RecognizedLanguage> elementLanguages = element.getRecognizedLanguages();
                    Point[] elementCornerPoints = element.getCornerPoints();
                    Rect elementFrame = element.getBoundingBox();
                }
            }
        }
        onPostExecute(resultText);
    }


    //button text reset
    protected void onPostExecute(String result) {
            textView.setText(result);

            button.setEnabled(true);
            button.setText("텍스트 인식");
    }


    //lowercase text conversion
    protected String TransformMinuscule(String text) {

        int tmp;
        String result_text = "";

        for(int i = 0; i < text.length(); i++){
           tmp = (int)text.charAt(i);

           if((65 <= tmp) && (tmp <= 90)) {
               result_text += (char) (tmp + 32);
           }else if(10 == tmp){
               result_text += "";
           }else {
               result_text += (char)tmp;
           }
        }
        return result_text;
    }



    //If the object remains, it stops execution and removes it from memory.
    @Override
    protected void onDestroy() {
        super.onDestroy();

        if(tts_en != null){
            tts_en.stop();
            tts_en.shutdown();
            tts_en = null;
        }
        if(tts_ko != null){
            tts_ko.stop();
            tts_ko.shutdown();
            tts_ko = null;
        }
    }




}
