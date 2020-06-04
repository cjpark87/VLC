package edu.washington.cs.ubicomp.vlc;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Configuration;
import android.graphics.ImageFormat;
import android.graphics.SurfaceTexture;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.Image;
import android.media.ImageReader;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.util.SparseIntArray;
import android.view.Surface;
import android.view.TextureView;
import android.view.View;
import android.widget.TextView;
import android.widget.Toast;

import org.jtransforms.fft.DoubleFFT_1D;
import org.opencv.android.BaseLoaderCallback;
import org.opencv.android.LoaderCallbackInterface;
import org.opencv.android.OpenCVLoader;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Rect;
import org.opencv.core.Scalar;
import org.opencv.imgproc.Imgproc;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

public class MainActivity extends AppCompatActivity {
    private static final int REQUEST_CAMERA_PERMISSION_RESULT = 0;
    private static final int REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT = 1;
    private static final String TAG = "VLC";
    private AutoFitTextureView mTextureView;
    private AutoFitTextureView.SurfaceTextureListener mSurfaceTextureListener = new TextureView.SurfaceTextureListener() {
        @Override
        public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
            setupCamera(width, height);
            connectCamera();
        }

        @Override
        public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {

        }

        @Override
        public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
            return false;
        }

        @Override
        public void onSurfaceTextureUpdated(SurfaceTexture surface) {

        }
    };
    private CameraDevice mCameraDevice;
    private CameraDevice.StateCallback mCameraDeviceStateCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mCameraDevice = camera;
            startPreview();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            camera.close();
            mCameraDevice = null;
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            camera.close();
            mCameraDevice = null;
        }
    };
    private HandlerThread mBackgroundHandlerThread;
    private Handler mBackgroundHandler;
    private String mCameraId;
    private Size mPreviewSize;

    private CaptureRequest.Builder mCaptureRequestBuilder;

    private static SparseIntArray ORIENTATIONS = new SparseIntArray();

    private ArrayList<Double> means = new ArrayList<>();

    static {
        ORIENTATIONS.append(Surface.ROTATION_0, 0);
        ORIENTATIONS.append(Surface.ROTATION_90, 90);
        ORIENTATIONS.append(Surface.ROTATION_180, 180);
        ORIENTATIONS.append(Surface.ROTATION_270, 270);
    }

    private BlockingQueue<Image> imageQueue = new ArrayBlockingQueue<>(1000);
    private LinkedList<Double> meanQueue = new LinkedList<>();

    private int frameCount = 0;
    int FRAME_WINDOW = 6;
    int FFT_INDEX_0 = 1;
    int FFT_INDEX_1 = 2;
    int MAGNITUDE_DIFF = 10;
    boolean firstZero = true;
    ArrayList<Double> encodings = new ArrayList<>();
    String characters = "";
    ArrayList<Integer> bits = new ArrayList<>();
    //String currentCharacter = "";
    StringBuilder currentCharacter = new StringBuilder();
    boolean preambleReceived = false;
    private TextView mTextView;


    private static class CompareSizeByArea implements Comparator<Size> {

        @Override
        public int compare(Size lhs, Size rhs) {
            return Long.signum((long) lhs.getWidth() * lhs.getHeight() /
                    (long) rhs.getWidth() * rhs.getHeight());
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        loadOpenCV(this, mLoaderCallback);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        mTextureView = findViewById(R.id.textureView);
        mTextView = findViewById(R.id.textView);

        new DecoderAsyncTask().execute();
    }

    @Override
    protected void onResume() {
        super.onResume();

        startBackgroundThread();

        if (mTextureView.isAvailable()) {
            setupCamera(mTextureView.getWidth(), mTextureView.getHeight());
            connectCamera();
        } else {
            mTextureView.setSurfaceTextureListener(mSurfaceTextureListener);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == REQUEST_CAMERA_PERMISSION_RESULT) {
            if (grantResults[0] != PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(getApplicationContext(),
                        "Application will not run without camera services", Toast.LENGTH_SHORT).show();
            }
        }
        if (requestCode == REQUEST_WRITE_EXTERNAL_STORAGE_PERMISSION_RESULT) {
            if (grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this,
                        "Permission successfully granted!", Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this,
                        "App needs to save video to run", Toast.LENGTH_SHORT).show();
            }
        }
        if (ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && ActivityCompat.checkSelfPermission(MainActivity.this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(MainActivity.this,
                    new String[]{Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION},
                    1);
        }

    }

    @Override
    protected void onPause() {
        closeCamera();

        stopBackgroundThread();

        super.onPause();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        View decorView = getWindow().getDecorView();
        if (hasFocus) {
            decorView.setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION);
        }
    }

    ImageReader.OnImageAvailableListener mImageAvailable = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image = reader.acquireLatestImage();
            //Log.d(TAG, "image available!");
            if (image == null)
                return;

            if (frameCount < 15) {
                frameCount++;
                image.close();
                return;
            }

            imageQueue.add(image);
        }


    };

    private ImageReader mImageReader;

    private void setupCamera(int width, int height) {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            for (String cameraId : cameraManager.getCameraIdList()) {
                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(cameraId);
                if (cameraCharacteristics.get(CameraCharacteristics.LENS_FACING) !=
                        CameraCharacteristics.LENS_FACING_BACK) {
                    continue;
                }
                StreamConfigurationMap map = cameraCharacteristics.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
                mPreviewSize = chooseOptimalSize(map.getOutputSizes(SurfaceTexture.class), width,
                        height);

                // Update the aspect ratio of the TextureView to the size of the preview
                int orientation = getResources().getConfiguration().orientation;
                if (orientation == Configuration.ORIENTATION_LANDSCAPE)
                    mTextureView.setAspectRatio(mPreviewSize.getWidth(), mPreviewSize.getHeight());
                else
                    mTextureView.setAspectRatio(mPreviewSize.getHeight(), mPreviewSize.getWidth());

                mCameraId = cameraId;

                mImageReader = ImageReader.newInstance(mPreviewSize.getWidth(),
                        mPreviewSize.getHeight(),
                        ImageFormat.YUV_420_888, 3);
                mImageReader.setOnImageAvailableListener(mImageAvailable, mBackgroundHandler);
                return;
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void connectCamera() {
        CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED) {
                    cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
                } else {
                    if (shouldShowRequestPermissionRationale(Manifest.permission.CAMERA)) {
                        Toast.makeText(this,
                                "Video app required access to camera", Toast.LENGTH_SHORT).show();
                    }
                    requestPermissions(new String[]{Manifest.permission.CAMERA}, REQUEST_CAMERA_PERMISSION_RESULT);
                }

            } else {
                cameraManager.openCamera(mCameraId, mCameraDeviceStateCallback, mBackgroundHandler);
            }
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void startPreview() {
        SurfaceTexture surfaceTexture = mTextureView.getSurfaceTexture();
        surfaceTexture.setDefaultBufferSize(mPreviewSize.getWidth(), mPreviewSize.getHeight());
        Surface previewSurface = new Surface(surfaceTexture);

        try {
            mCaptureRequestBuilder = mCameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            mCaptureRequestBuilder.addTarget(previewSurface);
            Surface readerSurface = mImageReader.getSurface();
            mCaptureRequestBuilder.addTarget(readerSurface);
            mCameraDevice.createCaptureSession(Arrays.asList(previewSurface, readerSurface),
                    new CameraCaptureSession.StateCallback() {
                        @Override
                        public void onConfigured(CameraCaptureSession session) {
                            try {
                                CameraManager cameraManager = (CameraManager) getSystemService(Context.CAMERA_SERVICE);
                                CameraCharacteristics cameraCharacteristics = cameraManager.getCameraCharacteristics(mCameraId);
                                Range<Integer>[] fpsRanges = cameraCharacteristics.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);
                                Log.d("FPS", "SYNC_MAX_LATENCY_PER_FRAME_CONTROL: " + Arrays.toString(fpsRanges));

                                mCaptureRequestBuilder.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,fpsRanges[fpsRanges.length-1]);

                                session.setRepeatingRequest(mCaptureRequestBuilder.build(),
                                        null, mBackgroundHandler);
                            } catch (CameraAccessException e) {
                                e.printStackTrace();
                            }
                        }

                        @Override
                        public void onConfigureFailed(CameraCaptureSession session) {
                            Toast.makeText(getApplicationContext(),
                                    "Unable to setup camera preview", Toast.LENGTH_SHORT).show();

                        }
                    }, null);
        } catch (CameraAccessException e) {
            e.printStackTrace();
        }
    }

    private void closeCamera() {
        if (mCameraDevice != null) {
            mCameraDevice.close();
            mCameraDevice = null;
        }
    }

    private void startBackgroundThread() {
        mBackgroundHandlerThread = new HandlerThread("VLC");
        mBackgroundHandlerThread.start();
        mBackgroundHandler = new Handler(mBackgroundHandlerThread.getLooper());
    }

    private void stopBackgroundThread() {
        mBackgroundHandlerThread.quitSafely();
        try {
            mBackgroundHandlerThread.join();
            mBackgroundHandlerThread = null;
            mBackgroundHandler = null;
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
    }


    private static Size chooseOptimalSize(Size[] choices, int width, int height) {
        List<Size> bigEnough = new ArrayList<Size>();
        Log.d(TAG, String.format("preview size: %d, %d", width, height));
        for (Size option : choices) {
            Log.d(TAG, "Size option: " + option.toString());
            //TODO: change HARD-CODED values
            if ((double) option.getWidth() / (double) option.getHeight() == 16.0 / 9.0 &&
                    option.getWidth() >= width) {
                Log.d(TAG, "----Size option: " + option.toString());
                bigEnough.add(option);
            }
        }
        if (bigEnough.size() > 0) {
            Size size = Collections.max(bigEnough, new CompareSizeByArea());
            Log.d(TAG, String.format("selected size: %s", size.toString()));
            return size;
        }
        return choices[0];

    }

    //opencv loading
    public static void loadOpenCV(Context context, BaseLoaderCallback mLoaderCallback) {
        if (!OpenCVLoader.initDebug()) {
            Log.d(TAG, "Internal OpenCV library not found. Using OpenCV Manager for initialization");
            OpenCVLoader.initAsync(OpenCVLoader.OPENCV_VERSION, context, mLoaderCallback);
        } else {
            Log.d(TAG, "OpenCV library found inside package. Using it!");
            mLoaderCallback.onManagerConnected(LoaderCallbackInterface.SUCCESS);
        }

    }

    private BaseLoaderCallback mLoaderCallback = new BaseLoaderCallback(this) {
        @Override
        public void onManagerConnected(int status) {
            switch (status) {
                case LoaderCallbackInterface.SUCCESS: {
                    Log.i(TAG, "OpenCV loaded successfully");
                    Mat a = new Mat();
                }
                break;
                default: {
                    super.onManagerConnected(status);
                }
                break;
            }
        }
    };

    private class DecoderAsyncTask extends AsyncTask<Void, Void, Void> {

        @Override
        protected Void doInBackground(Void... voids) {
            while (true) {
                while (imageQueue.size() < 1) {
                }

                Log.d(TAG, "Queue size: " + imageQueue.size());
                long start = System.currentTimeMillis();
                Image image = imageQueue.remove();
                Mat rgbaMat = ImageUtil.imageToRGBMat(image);
                rgbaMat = rgbaMat.submat(new Rect(rgbaMat.width() / 2 - 150, rgbaMat.height() / 2 - 50, 50, 50));
                Mat grayMat = new Mat();
                Imgproc.cvtColor(rgbaMat, grayMat, Imgproc.COLOR_RGBA2GRAY);
                Scalar mean = Core.mean(grayMat);

                //Log.d(TAG, "mean: " + mean.val[0]);

                meanQueue.add(mean.val[0]);

                if (meanQueue.size() >= FRAME_WINDOW) {
                    //FFT
                    DoubleFFT_1D fft = new DoubleFFT_1D(FRAME_WINDOW); // 1024 is size of array
                    double[] fftData = new double[FRAME_WINDOW * 2];
                    double[] magnitude = new double[FRAME_WINDOW];

                    for (int i = 0; i < FRAME_WINDOW; i++) {
                        fftData[2 * i] = meanQueue.get(i);  //da controllare
                        fftData[2 * i + 1] = 0;
                    }
                    fft.complexForward(fftData);

                    for (int i = 0; i < FRAME_WINDOW / 2 + 1; i++) {
                        magnitude[i] = Math.sqrt((fftData[2 * i] * fftData[2 * i]) + (fftData[2 * i + 1] * fftData[2 * i + 1]));
                        Log.d("VLC", String.format("FFT %d %.5f %.5f", i, (double) i * 30.0 / (double) FRAME_WINDOW, magnitude[i]));
                    }

                    //adding 0/1
                    double encoding = 0.5;
                    if (magnitude[FFT_INDEX_0] - magnitude[FFT_INDEX_1] > MAGNITUDE_DIFF) {
                        encoding = 0.0;
                    } else if (magnitude[FFT_INDEX_1] - magnitude[FFT_INDEX_0] > MAGNITUDE_DIFF) {
                        encoding = 1.0;
                    } else {
                        encoding = 0.5;
                    }

                    Log.d("VLC", String.format("FFT %.5f: %.5f, %.5f: %.5f, %.2f", (double) FFT_INDEX_0 * 30.0 / (double) FRAME_WINDOW, magnitude[FFT_INDEX_0], (double) FFT_INDEX_1 * 30.0 / (double) FRAME_WINDOW, magnitude[FFT_INDEX_1], encoding));

                    if (magnitude[FFT_INDEX_0] > 5 || magnitude[FFT_INDEX_1] > 5) {
                        encodings.add(encoding);
                        Log.d(TAG, "Encoding: " + encoding);
                    }

                    if (encodings.size() > 1) {
                        if (!encodings.get(encodings.size() - 1).equals(encodings.get(encodings.size() - 2))) {
                            double[] segment = new double[encodings.size() - 1];
                            Double lastEnc = encodings.get(encodings.size() - 1);

                            for (int i = 0; i < segment.length; i++) {
                                segment[i] = encodings.get(i);
                            }

                            encodings.clear();
                            encodings.add(lastEnc);

                            //decide bit
                            int count_0 = 0;
                            int count_1 = 0;

                            for (double d : segment) {
                                if (d == 0.0) {
                                    count_0++;
                                } else if (d == 1.0) {
                                    count_1++;
                                }
                            }

                            if (count_0 > count_1) {
                                if (firstZero) {
                                    currentCharacter.append('0');
                                    bits.add(0);
                                    firstZero = false;
                                } else {
                                    for (int i = 0; i < (Math.round((double) segment.length / (double) FRAME_WINDOW)); i++) {
                                        currentCharacter.append('0');
                                        bits.add(0);
                                    }
                                }
                            } else if (count_0 < count_1) {
                                for (int i = 0; i < (Math.round((double)segment.length/(double)FRAME_WINDOW)); i++) {
                                    currentCharacter.append('1');
                                    bits.add(1);
                                }
                            }

                            Log.d(TAG, "Segment size: " + segment.length + ", " + (Math.round((double)segment.length/(double)FRAME_WINDOW)) + ", " + Arrays.toString(segment) + ", " + currentCharacter.toString());

                            if (currentCharacter.length() > 8) {
                                String currString = currentCharacter.substring(0, 8);
                                currentCharacter = currentCharacter.delete(0, 8);

                                char c = (char) Integer.parseInt(currString, 2);
                                if (preambleReceived) {
                                    characters += c;
                                    Log.d(TAG, "Character: " + c + ", " + characters + ", " + currString + ", " + bits);
                                    runOnUiThread(new Runnable() {
                                        @Override
                                        public void run() {
                                            mTextView.setText(characters);
                                        }
                                    });
                                }

                                if (characters.length() == 0 && c == 'U') {
                                    preambleReceived = true;
                                }
                            }
                        }
                    }

                    meanQueue.remove();
                }

                imageQueue.remove(image);
                image.close();
                Log.d(TAG, "processing time: " + (System.currentTimeMillis() - start));
            }
        }
    }
}