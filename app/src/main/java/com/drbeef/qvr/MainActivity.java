package com.drbeef.qvr;

import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.ActivityInfo;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.os.Bundle;
import android.os.Vibrator;
import android.util.Log;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.InputDevice;
import android.view.Window;
import android.view.WindowManager;

import com.google.vrtoolkit.cardboard.CardboardActivity;
import com.google.vrtoolkit.cardboard.CardboardView;
import com.google.vrtoolkit.cardboard.Eye;
import com.google.vrtoolkit.cardboard.HeadTransform;
import com.google.vrtoolkit.cardboard.Viewport;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.ShortBuffer;

import javax.microedition.khronos.egl.EGLConfig;


public class MainActivity
        extends CardboardActivity
        implements CardboardView.StereoRenderer, QVRCallback
{
    private static final String TAG = "QVR";

    private static final int GL_RGBA8 = 0x8058;

    private int[] currentFBO = new int[1];
    private int fboEyeResolution = 0;
    //This is set when the user opts to use a different resolution to the one picked as the default
    private int desiredEyeBufferResolution = -1;

    //Head orientation
    private float[] eulerAngles = new float[3];

    private int MONO = 0;
    private int STEREO = 1;

    private int mStereoMode = STEREO;
    private int eyeID = 0;

    /**
     * 0 = no big screen (in game)
     * 1 = big screen whilst menu or console active
     * 2 = big screen all the time
     */
    private int bigScreen = 1;

    //-1 means start button isn't pressed
    private long startButtonDownCounter = -1;
    //Don't allow the trigger to fire more than once per 200ms
    private long triggerTimeout = 0;

    private Vibrator vibrator;
    private float M_PI = 3.14159265358979323846f;
    public static AudioCallback mAudio;
    //Read these from a file and pass through
    String commandLineParams = new String("");

    private CardboardView cardboardView;

    private FloatBuffer screenVertices;
    private FloatBuffer splashScreenVertices;

    private int positionParam;
    private int texCoordParam;
    private int samplerParam;
    private int modelViewProjectionParam;

    private float[] modelScreen;
    private float[] camera;
    private float[] view;
    private float[] modelViewProjection;
    private float[] modelView;

    private float gameScreenScale = 7f;
    private float gameScreenDistance = 8f;
    private float splashScreenScale = 2f;
    private float splashScreenDistance = 8f;
    private float bigScreenScale = 4f;
    private float bigScreenDistance = 8f;

    public static final String vs_Image =
            "uniform mat4 u_MVPMatrix;" +
            "attribute vec4 a_Position;" +
            "attribute vec2 a_texCoord;" +
            "varying vec2 v_texCoord;" +
            "void main() {" +
            "  gl_Position = u_MVPMatrix * a_Position;" +
            "  v_texCoord = a_texCoord;" +
            "}";


    public static final String fs_Image =
            "precision mediump float;" +
            "varying vec2 v_texCoord;" +
            "uniform sampler2D s_texture;" +
            "void main() {" +
            "  gl_FragColor = texture2D( s_texture, v_texCoord );" +
            "}";

    public static int loadShader(int type, String shaderCode){
        int shader = GLES20.glCreateShader(type);
        GLES20.glShaderSource(shader, shaderCode);
        GLES20.glCompileShader(shader);
        return shader;
    }

    //FBO render eye buffer
    private QVRFBO fbo;

    // Geometric variables
    public static float vertices[];
    public static final short[] indices = new short[] {0, 1, 2, 0, 2, 3};
    public static final float uvs[] =  new float[] {
            0.0f, 1.0f,
            0.0f, 0.0f,
            1.0f, 0.0f,
            1.0f, 1.0f
    };

    public static final float[] SCREEN_COORDS = new float[] {
            -1.0f, 1.0f, 1.0f,
            -1.0f, -1.0f, 1.0f,
            1.0f, -1.0f, 1.0f,
            1.0f, 1.0f, 1.0f
    };

    public static final float[] SPLASH_SCREEN_COORDS = new float[] {
            -1.3f, -1.0f, 0.0f,
            -1.3f, 1.0f, 0.0f,
            1.3f, 1.0f, 0.0f,
            1.3f, -1.0f, 0.0f
    };

    public FloatBuffer vertexBuffer;
    public ShortBuffer listBuffer;
    public FloatBuffer uvBuffer;

    //Shader Program
    public static int sp_Image;

    private DownloadTask mDownloadTask = null;

    public static boolean mQVRInitialised = false;
    public static boolean mVRModeChanged = true;
    public static int mVRMode = 2;
    public static boolean sideBySide = false;
    //Can't rebuild eye buffers until surface changed flag recorded
    public static boolean mSurfaceChanged = false;

    private int VRMODE_OFF = 0;
    private int VRMODE_SIDEBYSIDE = 1;
    private int VRMODE_CARDBOARD = 2;

    private boolean mShowingSpashScreen = true;
    private int[] splashTexture = new int[1];
    private MediaPlayer mPlayer;


    static {
        try {
            Log.i("JNI", "Trying to load libqvr.so");
            System.loadLibrary("qvr");
        } catch (UnsatisfiedLinkError ule) {
            Log.e("JNI", "WARNING: Could not load libqvr.so");
        }
    }

    public void copy_asset(String name, String folder) {
        File f = new File(folder + name);
        if (!f.exists()) {
            //Ensure we have an appropriate folder
            new File(folder).mkdirs();
            _copy_asset(name, folder + name);
        }
    }

    public void _copy_asset(String name_in, String name_out) {
        AssetManager assets = this.getAssets();

        try {
            InputStream in = assets.open(name_in);
            OutputStream out = new FileOutputStream(name_out);

            copy_stream(in, out);

            out.close();
            in.close();

        } catch (Exception e) {

            e.printStackTrace();
        }

    }

    public static void copy_stream(InputStream in, OutputStream out)
            throws IOException {
        byte[] buf = new byte[512];
        while (true) {
            int count = in.read(buf);
            if (count <= 0)
                break;
            out.write(buf, 0, count);
        }
    }

    static boolean CreateFBO( QVRFBO fbo, int offset, int width, int height)
    {
        Log.d(TAG, "CreateFBO");
        // Create the color buffer texture.
        GLES20.glGenTextures(1, fbo.ColorTexture, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fbo.ColorTexture[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GL_RGBA8, width, height, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        // Create depth buffer.
        GLES20.glGenRenderbuffers(1, fbo.DepthBuffer, 0);
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, fbo.DepthBuffer[0]);
        GLES20.glRenderbufferStorage(GLES20.GL_RENDERBUFFER, GLES11Ext.GL_DEPTH_COMPONENT24_OES, width, height);
        GLES20.glBindRenderbuffer(GLES20.GL_RENDERBUFFER, 0);

        // Create the frame buffer.
        GLES20.glGenFramebuffers(1, fbo.FrameBuffer, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo.FrameBuffer[0]);
        GLES20.glFramebufferRenderbuffer(GLES20.GL_FRAMEBUFFER, GLES20.GL_DEPTH_ATTACHMENT, GLES20.GL_RENDERBUFFER, fbo.DepthBuffer[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, fbo.ColorTexture[0], 0);
        int renderFramebufferStatus = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if ( renderFramebufferStatus != GLES20.GL_FRAMEBUFFER_COMPLETE )
        {
            Log.d(TAG, "Incomplete frame buffer object!!");
            return false;
        }

        fbo.width = width;
        fbo.height = height;

        return true;
    }

    static void DestroyFBO( QVRFBO fbo )
    {
        GLES20.glDeleteFramebuffers( 1, fbo.FrameBuffer, 0 );
        fbo.FrameBuffer[0] = 0;
        GLES20.glDeleteRenderbuffers( 1, fbo.DepthBuffer, 0 );
        fbo.DepthBuffer[0] = 0;
        GLES20.glDeleteTextures( 1, fbo.ColorTexture, 0 );
        fbo.ColorTexture[0] = 0;
        fbo.width = 0;
        fbo.height = 0;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        requestWindowFeature(Window.FEATURE_NO_TITLE);

        setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);
        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_main);
        cardboardView = (CardboardView) findViewById(R.id.cardboard_view);
        cardboardView.setEGLConfigChooser(5, 6, 5, 0, 16, 0);
        cardboardView.setLowLatencyModeEnabled(true);
        cardboardView.setRenderer(this);
        setCardboardView(cardboardView);

        modelScreen = new float[16];
        camera = new float[16];
        view = new float[16];
        modelViewProjection = new float[16];
        modelView = new float[16];

        vibrator = (Vibrator) getSystemService(Context.VIBRATOR_SERVICE);

        //At the very least ensure we have a directory containing a config file
        copy_asset("config.cfg", QVRConfig.GetFullWorkingFolder() + "id1/");
        copy_asset("commandline.txt", QVRConfig.GetFullWorkingFolder());

        //See if user is trying to use command line params
        BufferedReader br;
        try {
            br = new BufferedReader(new FileReader(QVRConfig.GetFullWorkingFolder() + "commandline.txt"));
            String s;
            StringBuilder sb=new StringBuilder(0);
            while ((s=br.readLine())!=null)
                sb.append(s + " ");
            br.close();

            commandLineParams = new String(sb.toString());
        } catch (FileNotFoundException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } catch (IOException e) {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }

        if (commandLineParams.contains("-game"))
        {
            //No need to download, user is playing something else
        }
        else
        {
            File f = new File(QVRConfig.GetFullWorkingFolder() + "id1/pak0.pak");
            if (!f.exists()) {
                AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(
                        this);

                // set title
                alertDialogBuilder.setTitle("No game assets found");

                // set dialog message
                alertDialogBuilder
                        .setMessage("Would you like to download the shareware version of Quake (8MB)?\n\nIf you own or purchase the full game (On Steam: http://store.steampowered.com/app/2310/) you can click \'Cancel\' and copy the pak files to the folder:\n\n{phonememory}/QVR/id1")
                        .setCancelable(false)
                        .setPositiveButton("Download", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                MainActivity.this.startDownload();
                            }
                        })
                        .setNegativeButton("Cancel", new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int id) {
                                dialog.cancel();
                            }
                        });

                // create alert dialog
                AlertDialog alertDialog = alertDialogBuilder.create();

                // show it
                alertDialog.show();
            }
        }

        //Create the FBOs
        fbo = new QVRFBO();

        if (mAudio==null)
        {
            mAudio = new AudioCallback();
        }

        QVRJNILib.setCallbackObjects(mAudio, this);

    }

    public void startDownload()
    {
        mDownloadTask = new DownloadTask();
        mDownloadTask.set_context(MainActivity.this);
        mDownloadTask.execute();
    }

    @Override
    public void onRendererShutdown() {
        Log.i(TAG, "onRendererShutdown");
    }

    @Override
    public void onSurfaceChanged(int width, int height)
    {
        Log.d(TAG, "onSurfaceChanged width = " + width + "  height = " + height);
        mSurfaceChanged = true;
    }

    @Override
    public void onSurfaceCreated(EGLConfig config) {
         Log.i(TAG, "onSurfaceCreated");

        ByteBuffer bbVertices = ByteBuffer.allocateDirect(SCREEN_COORDS.length * 4);
        bbVertices.order(ByteOrder.nativeOrder());
        screenVertices = bbVertices.asFloatBuffer();
        screenVertices.put(SCREEN_COORDS);
        screenVertices.position(0);

        bbVertices = ByteBuffer.allocateDirect(SPLASH_SCREEN_COORDS.length * 4);
        bbVertices.order(ByteOrder.nativeOrder());
        splashScreenVertices = bbVertices.asFloatBuffer();
        splashScreenVertices.put(SPLASH_SCREEN_COORDS);
        splashScreenVertices.position(0);

        // initialize byte buffer for the draw list
        ByteBuffer dlb = ByteBuffer.allocateDirect(indices.length * 2);
        dlb.order(ByteOrder.nativeOrder());
        listBuffer = dlb.asShortBuffer();
        listBuffer.put(indices);
        listBuffer.position(0);

         // Create the shaders, images
         int vertexShader = loadShader(GLES20.GL_VERTEX_SHADER, vs_Image);
         int fragmentShader = loadShader(GLES20.GL_FRAGMENT_SHADER, fs_Image);

         sp_Image = GLES20.glCreateProgram();             // create empty OpenGL ES Program
         GLES20.glAttachShader(sp_Image, vertexShader);   // add the vertex shader to program
         GLES20.glAttachShader(sp_Image, fragmentShader); // add the fragment shader to program
         GLES20.glLinkProgram(sp_Image);                  // creates OpenGL ES program executable

        positionParam = GLES20.glGetAttribLocation(sp_Image, "a_Position");
        texCoordParam = GLES20.glGetAttribLocation(sp_Image, "a_texCoord");
        modelViewProjectionParam = GLES20.glGetUniformLocation(sp_Image, "u_MVPMatrix");
        samplerParam = GLES20.glGetUniformLocation(sp_Image, "s_texture");


        GLES20.glEnableVertexAttribArray(positionParam);
        GLES20.glEnableVertexAttribArray(texCoordParam);

        // Build the camera matrix
        Matrix.setLookAtM(camera, 0, 0.0f, 0.0f, 0.01f, 0.0f, 0.0f, 0.0f, 0.0f, 1.0f, 0.0f);

        //Start intro music
        mPlayer = MediaPlayer.create(this, R.raw.m010912339);
        mPlayer.start();

        //Load bitmap for splash screen
        splashTexture[0] = 0;
        GLES20.glGenTextures(1, splashTexture, 0);

        Bitmap bmp = null;
        try {
            AssetManager assets = this.getAssets();
            InputStream in = assets.open("splash.jpg");
            bmp = BitmapFactory.decodeStream(in);
            in.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        // Bind texture to texturename
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, splashTexture[0]);

        // Set filtering
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);

        // Set wrapping mode
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);

        // Load the bitmap into the bound texture.
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);

        //unbind
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

        // We are done using the bitmap so we should recycle it.
        //bmp.recycle();

    }

    public void BigScreenMode(int mode)
    {
        if (mode == -1)
            bigScreen = 1;
        else if (bigScreen != 2)
            bigScreen = mode;
    }

    public void SwitchStereoMode(int stereo_mode)
    {
        mStereoMode = stereo_mode;
    }

    int getDesiredfboEyeResolution(int viewportWidth) {

        desiredEyeBufferResolution = QVRJNILib.getEyeBufferResolution();
        if (desiredEyeBufferResolution != 0)
            return desiredEyeBufferResolution;

        //Select based on viewport width
        if (viewportWidth > 1024)
            desiredEyeBufferResolution = 1024;
        else if (viewportWidth > 512)
            desiredEyeBufferResolution = 512;
        else
            desiredEyeBufferResolution = 256;

        return desiredEyeBufferResolution;
    }

    @Override
    public void onNewFrame(HeadTransform headTransform) {
        if (mQVRInitialised) {
            headTransform.getEulerAngles(eulerAngles, 0);

            QVRJNILib.onNewFrame(-eulerAngles[0] / (M_PI / 180.0f), eulerAngles[1] / (M_PI / 180.0f), -eulerAngles[2] / (M_PI / 180.0f));

            //Check to see if we should update the eye buffer resolution
            int checkRes = QVRJNILib.getEyeBufferResolution();
            if (checkRes != 0 && checkRes != desiredEyeBufferResolution)
                mVRModeChanged = true;
        }
    }

    @Override
    public void onDrawEye(Eye eye) {
        if (mVRModeChanged)
        {
            if (!mSurfaceChanged)
                return;

            Log.i(TAG, "mVRModeChanged");
            if (fbo.FrameBuffer[0] != 0)
                DestroyFBO(fbo);

            if (mVRMode == VRMODE_SIDEBYSIDE) {
                CreateFBO(fbo, 0, eye.getViewport().width / 2, eye.getViewport().height);
                QVRJNILib.setResolution(eye.getViewport().width / 2, eye.getViewport().height);
            }
            else if (mVRMode == VRMODE_CARDBOARD) {
                fboEyeResolution = getDesiredfboEyeResolution(eye.getViewport().width);
                CreateFBO(fbo, 0, fboEyeResolution, fboEyeResolution);
                QVRJNILib.setResolution(fboEyeResolution, fboEyeResolution);
            }
            else // VRMODE_OFF
            {
                CreateFBO(fbo, 0, eye.getViewport().width, eye.getViewport().height);
                QVRJNILib.setResolution(eye.getViewport().width, eye.getViewport().height);
            }

            SetupUVCoords();

            //Reset our orientation
            cardboardView.resetHeadTracker();

            mVRModeChanged = false;
        }

        if (!mQVRInitialised && !mShowingSpashScreen)
        {
            QVRJNILib.initialise(QVRConfig.GetFullWorkingFolder(), commandLineParams);
            mQVRInitialised = true;
        }

        if (mQVRInitialised || mShowingSpashScreen) {

            //Record the curent fbo
            GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, currentFBO, 0);

            for (int i = 0; i < 2; ++i) {

                if (mShowingSpashScreen) {
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);
                    GLES20.glScissor(eye.getViewport().x, eye.getViewport().y,
                            eye.getViewport().width, eye.getViewport().height);
                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
                }
                else if (mStereoMode == STEREO ||
                        (mStereoMode == MONO && eye.getType() < 2)) {
                    //Bind our special fbo
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo.FrameBuffer[0]);
                    GLES20.glEnable(GLES20.GL_DEPTH_TEST);
                    GLES20.glDepthFunc(GLES20.GL_LEQUAL);
                    GLES20.glEnable(GLES20.GL_SCISSOR_TEST);

                    if (mVRMode == VRMODE_SIDEBYSIDE) {
                        GLES20.glScissor(0, 0, eye.getViewport().width / 2, eye.getViewport().height);
                    } else if (mVRMode == VRMODE_CARDBOARD) {
                        GLES20.glScissor(0, 0, fboEyeResolution, fboEyeResolution);
                    } else {
                        GLES20.glScissor(0, 0, eye.getViewport().width, eye.getViewport().height);
                    }

                    GLES20.glClearColor(0.0f, 0.0f, 0.0f, 1.0f);
                    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

                    //Decide which eye we are drawing
                    if (mStereoMode == MONO)
                        eyeID = 0;
                    else if (mVRMode == 0)
                        eyeID = 0;
                    else if (mVRMode == VRMODE_SIDEBYSIDE)
                        eyeID = i;
                    else // mStereoMode == StereoMode.STEREO  -  Default behaviour for VR mode
                        eyeID = eye.getType() - 1;

                    //We only draw from QVR if we arent showing the splash
                    if (!mShowingSpashScreen)
                        QVRJNILib.onDrawEye(eyeID, 0, 0);

                    //Finished rendering to our frame buffer, now draw this to the target framebuffer
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, currentFBO[0]);
                }

                GLES20.glDisable(GLES20.GL_SCISSOR_TEST);

                if (mVRMode == VRMODE_SIDEBYSIDE) {
                    GLES20.glViewport(eye.getViewport().x + ((eye.getViewport().width/2) * i),
                            eye.getViewport().y,
                            eye.getViewport().width/2,
                            eye.getViewport().height);
                }
                else
                {
                    GLES20.glViewport(eye.getViewport().x,
                            eye.getViewport().y,
                            eye.getViewport().width,
                            eye.getViewport().height);
                }

                GLES20.glUseProgram(sp_Image);

                // Build the ModelView and ModelViewProjection matrices
                // for calculating screen position.
                float[] perspective = eye.getPerspective(0.1f, 100.0f);
                Matrix.setIdentityM(modelScreen, 0);

                if ((bigScreen != 0) && mVRMode > 0) {
                    // Apply the eye transformation to the camera.
                    Matrix.multiplyMM(view, 0, eye.getEyeView(), 0, camera, 0);

                    // Set the position of the screen
                    if (mShowingSpashScreen) {
                        // Object first appears directly in front of user.
                        Matrix.translateM(modelScreen, 0, 0, 0, -splashScreenDistance);
                        Matrix.scaleM(modelScreen, 0, splashScreenScale, splashScreenScale, 1.0f);

                        float mAngle = 360.0f * (float) ((System.currentTimeMillis() % 3000) / 3000.0f);
                        Matrix.rotateM(modelScreen, 0, mAngle, 0.0f, 1.0f, 0.0f);
                        Matrix.multiplyMM(modelView, 0, view, 0, modelScreen, 0);
                        Matrix.multiplyMM(modelViewProjection, 0, perspective, 0, modelView, 0);
                        GLES20.glVertexAttribPointer(positionParam, 3, GLES20.GL_FLOAT, false, 0, splashScreenVertices);
                    } else {
                        // Object first appears directly in front of user.
                        Matrix.translateM(modelScreen, 0, 0, 0, -bigScreenDistance);
                        Matrix.scaleM(modelScreen, 0, bigScreenScale*1.2f, bigScreenScale, 1.0f);

                        Matrix.multiplyMM(modelView, 0, view, 0, modelScreen, 0);
                        Matrix.multiplyMM(modelViewProjection, 0, perspective, 0, modelView, 0);
                        GLES20.glVertexAttribPointer(positionParam, 3, GLES20.GL_FLOAT, false, 0, screenVertices);
                    }
                }
                else if (mVRMode == VRMODE_CARDBOARD)
                {
                    //Don't use head/eye transformation
                    Matrix.translateM(modelScreen, 0, 0, 0, -gameScreenDistance);
                    Matrix.scaleM(modelScreen, 0, gameScreenScale, gameScreenScale, 1.0f);

                    // Build the ModelView and ModelViewProjection matrices
                    // for calculating screen position.
                    Matrix.multiplyMM(modelView, 0, camera, 0, modelScreen, 0);
                    Matrix.multiplyMM(modelViewProjection, 0, perspective, 0, modelView, 0);
                    GLES20.glVertexAttribPointer(positionParam, 3, GLES20.GL_FLOAT, false, 0, screenVertices);
                }
                else //Side by side
                {
                    int w = (int) eye.getViewport().width;
                    int h = (int) eye.getViewport().height;
                    int x = (int) 0;
                    int y = (int) 0;

                    SetupTriangle(x, y, w, h);

                    // Calculate the projection and view transformation
                    Matrix.orthoM(view, 0, 0, eye.getViewport().width, 0, eye.getViewport().height, 0, 50);
                    Matrix.multiplyMM(modelViewProjection, 0, view, 0, camera, 0);

                    // Prepare the triangle coordinate data
                    GLES20.glVertexAttribPointer(positionParam, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer);
                }

                // Prepare the texturecoordinates
                GLES20.glVertexAttribPointer(texCoordParam, 2, GLES20.GL_FLOAT, false, 0, uvBuffer);

                // Apply the projection and view transformation
                GLES20.glUniformMatrix4fv(modelViewProjectionParam, 1, false, modelViewProjection, 0);

                // Bind texture to fbo's color texture
                GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
                IntBuffer activeTex0 = IntBuffer.allocate(2);
                GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D, activeTex0);

                if (mShowingSpashScreen)
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, splashTexture[0]);
                else
                    GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, fbo.ColorTexture[0]);

                // Set the sampler texture unit to our fbo's color texture
                GLES20.glUniform1i(samplerParam, 0);

                // Draw the triangles
                GLES20.glDrawElements(GLES20.GL_TRIANGLES, 6, GLES20.GL_UNSIGNED_SHORT, listBuffer);

                int error = GLES20.glGetError();
                if (error != GLES20.GL_NO_ERROR)
                    Log.d(TAG, "GLES20 Error = " + error);

                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, activeTex0.get(0));

                //Only loop round again for side by side
                if (mVRMode != VRMODE_SIDEBYSIDE && i == 0)
                    break;
            }
        }
    }

    @Override
    public void onFinishFrame(Viewport viewport) {
        if (mQVRInitialised) {
             QVRJNILib.onFinishFrame();
        }
    }

    /**
     * Called when the Cardboard trigger is pulled.
     */
    //@Override
    public void onCardboardTrigger() {
        Log.i(TAG, "onCardboardTrigger");

        if (System.currentTimeMillis() - triggerTimeout > 200) {

            if (mQVRInitialised) {
                QVRJNILib.onKeyEvent(K_ENTER, KeyEvent.ACTION_DOWN, 0);
            }

            cardboardView.resetHeadTracker();

            dismissSplashScreen();

            triggerTimeout = System.currentTimeMillis();
        }
    }


    public int getCharacter(int keyCode, KeyEvent event)
    {
        if (keyCode==KeyEvent.KEYCODE_DEL) return '\b';
        return event.getUnicodeChar();
    }

    private void dismissSplashScreen()
    {
        if (mShowingSpashScreen) {
            mPlayer.stop();
            mPlayer.release();
            mShowingSpashScreen = false;
        }
    }

    @Override public boolean dispatchKeyEvent( KeyEvent event )
    {
        int keyCode = event.getKeyCode();
        int action = event.getAction();
        int character = 0;

        if ( action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP )
        {
            return super.dispatchKeyEvent( event );
        }
        if ( action == KeyEvent.ACTION_UP )
        {
            dismissSplashScreen();
            Log.v( TAG, "GLES3JNIActivity::dispatchKeyEvent( " + keyCode + ", " + action + " )" );
        }

        //Allow user to switch vr mode by holding the start button down
        if (keyCode == KeyEvent.KEYCODE_BUTTON_START)
        {
            if (action == KeyEvent.ACTION_DOWN &&
                    startButtonDownCounter == -1)
            {
                startButtonDownCounter = System.currentTimeMillis();

            }
            else if (action == KeyEvent.ACTION_UP)
            {
                startButtonDownCounter = -1;
            }
        }

        if (startButtonDownCounter != -1)
        {
            if ((System.currentTimeMillis() - startButtonDownCounter) > 2000)
            {
                //Switch VR mode
                startButtonDownCounter = -1;
                SwitchVRMode();
                //Now make sure qvr is aware!
                QVRJNILib.onSwitchVRMode(mVRMode);
            }
        }

        //Following buttons must not be handled here
        if (keyCode == KeyEvent.KEYCODE_VOLUME_UP ||
                keyCode == KeyEvent.KEYCODE_VOLUME_DOWN ||
                keyCode == KeyEvent.KEYCODE_BUTTON_THUMBL
                )
            return false;

        //Convert to QVR keys
        character = getCharacter(keyCode, event);
        int qKeyCode = convertKeyCode(keyCode, event);

        //Don't hijack all keys (volume etc)
        if (qKeyCode != -1)
            keyCode = qKeyCode;

        if (keyCode == K_ESCAPE)
            cardboardView.resetHeadTracker();

        if (mQVRInitialised) {
            QVRJNILib.onKeyEvent(keyCode, action, character);
        }

        return true;
    }

    private static float getCenteredAxis(MotionEvent event,
                                         int axis) {
        final InputDevice.MotionRange range = event.getDevice().getMotionRange(axis, event.getSource());
        if (range != null) {
            final float flat = range.getFlat();
            final float value = event.getAxisValue(axis);
            if (Math.abs(value) > flat) {
                return value;
            }
        }
        return 0;
    }


    //Save the game pad type once known:
    // 1 - Generic BT gamepad
    // 2 - Samsung gamepad that uses different axes for right stick
    int gamepadType = 0;

    int lTrigAction = KeyEvent.ACTION_UP;
    int rTrigAction = KeyEvent.ACTION_UP;

    @Override
    public boolean onGenericMotionEvent(MotionEvent event) {
        int source = event.getSource();
        int action = event.getAction();
        if ((source==InputDevice.SOURCE_JOYSTICK)||(event.getSource()==InputDevice.SOURCE_GAMEPAD))
        {
            if (event.getAction() == MotionEvent.ACTION_MOVE)
            {
                float x = getCenteredAxis(event, MotionEvent.AXIS_X);
                float y = -getCenteredAxis(event, MotionEvent.AXIS_Y);
                QVRJNILib.onTouchEvent( source, action, x, y );

                float z = getCenteredAxis(event, MotionEvent.AXIS_Z);
                float rz = -getCenteredAxis(event, MotionEvent.AXIS_RZ);
                //For the samsung game pad (uses different axes for the second stick)
                float rx = getCenteredAxis(event, MotionEvent.AXIS_RX);
                float ry = -getCenteredAxis(event, MotionEvent.AXIS_RY);

                //let's figure it out
                if (gamepadType == 0)
                {
                    if (z != 0.0f || rz != 0.0f)
                        gamepadType = 1;
                    else if (rx != 0.0f || ry != 0.0f)
                        gamepadType = 2;
                }

                switch (gamepadType)
                {
                    case 0:
                        break;
                    case 1:
                        QVRJNILib.onMotionEvent( source, action, z, rz );
                        break;
                    case 2:
                        QVRJNILib.onMotionEvent( source, action, rx, ry );
                        break;
                }

                //Fire weapon using shoulder trigger
                float axisRTrigger = max(event.getAxisValue(MotionEvent.AXIS_RTRIGGER),
                        event.getAxisValue(MotionEvent.AXIS_GAS));
                int newRTrig = axisRTrigger > 0.6 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
                if (rTrigAction != newRTrig)
                {
                    QVRJNILib.onKeyEvent( K_MOUSE1, newRTrig, 0);
                    rTrigAction = newRTrig;
                }

                //Run using L shoulder
                float axisLTrigger = max(event.getAxisValue(MotionEvent.AXIS_LTRIGGER),
                        event.getAxisValue(MotionEvent.AXIS_BRAKE));
                int newLTrig = axisLTrigger > 0.6 ? KeyEvent.ACTION_DOWN : KeyEvent.ACTION_UP;
                if (lTrigAction != newLTrig)
                {
                    QVRJNILib.onKeyEvent( K_SHIFT, newLTrig, 0);
                    lTrigAction = newLTrig;
                }
            }
        }
        return false;
    }

    private float max(float axisValue, float axisValue2) {
        return (axisValue > axisValue2) ? axisValue : axisValue2;
    }

    public static final int K_TAB = 9;
    public static final int K_ENTER = 13;
    public static final int K_ESCAPE = 27;
    public static final int K_SPACE	= 32;
    public static final int K_BACKSPACE	= 127;
    public static final int K_UPARROW = 128;
    public static final int K_DOWNARROW = 129;
    public static final int K_LEFTARROW = 130;
    public static final int K_RIGHTARROW = 131;
    public static final int K_ALT = 132;
    public static final int K_CTRL = 133;
    public static final int K_SHIFT = 134;
    public static final int K_F1 = 135;
    public static final int K_F2 = 136;
    public static final int K_F3 = 137;
    public static final int K_F4 = 138;
    public static final int K_F5 = 139;
    public static final int K_F6 = 140;
    public static final int K_F7 = 141;
    public static final int K_F8 = 142;
    public static final int K_F9 = 143;
    public static final int K_F10 = 144;
    public static final int K_F11 = 145;
    public static final int K_F12 = 146;
    public static final int K_INS = 147;
    public static final int K_DEL = 148;
    public static final int K_PGDN = 149;
    public static final int K_PGUP = 150;
    public static final int K_HOME = 151;
    public static final int K_END = 152;
    public static final int K_PAUSE = 153;
    public static final int K_NUMLOCK = 154;
    public static final int K_CAPSLOCK = 155;
    public static final int K_SCROLLOCK = 156;
    public static final int K_MOUSE1 = 512;
    public static final int K_MOUSE2 = 513;
    public static final int K_MOUSE3 = 514;
    public static final int K_MWHEELUP = 515;
    public static final int K_MWHEELDOWN = 516;
    public static final int K_MOUSE4 = 517;
    public static final int K_MOUSE5 = 518;

    public static int convertKeyCode(int keyCode, KeyEvent event)
    {
        switch(keyCode)
        {
            case KeyEvent.KEYCODE_FOCUS:
                return K_F1;
            case KeyEvent.KEYCODE_DPAD_UP:
            case KeyEvent.KEYCODE_W:
                return K_UPARROW;
            case KeyEvent.KEYCODE_DPAD_DOWN:
            case KeyEvent.KEYCODE_S:
                return K_DOWNARROW;
            case KeyEvent.KEYCODE_DPAD_LEFT:
                return 'a';
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                return 'd';
            case KeyEvent.KEYCODE_DPAD_CENTER:
                return K_CTRL;
            case KeyEvent.KEYCODE_ENTER:
                return K_ENTER;
			case KeyEvent.KEYCODE_BACK:
				return K_ESCAPE;
            case KeyEvent.KEYCODE_APOSTROPHE:
                return K_ESCAPE;
            case KeyEvent.KEYCODE_DEL:
                return K_BACKSPACE;
            case KeyEvent.KEYCODE_ALT_LEFT:
            case KeyEvent.KEYCODE_ALT_RIGHT:
                return K_ALT;
            case KeyEvent.KEYCODE_SHIFT_LEFT:
            case KeyEvent.KEYCODE_SHIFT_RIGHT:
                return K_SHIFT;
            case KeyEvent.KEYCODE_CTRL_LEFT:
            case KeyEvent.KEYCODE_CTRL_RIGHT:
                return K_CTRL;
            case KeyEvent.KEYCODE_INSERT:
                return K_INS;
            case 122:
                return K_HOME;
            case KeyEvent.KEYCODE_FORWARD_DEL:
                return K_DEL;
            case 123:
                return K_END;
            case KeyEvent.KEYCODE_ESCAPE:
                return K_ESCAPE;
            case KeyEvent.KEYCODE_TAB:
                return K_TAB;
            case KeyEvent.KEYCODE_F1:
                return K_F1;
            case KeyEvent.KEYCODE_F2:
                return K_F2;
            case KeyEvent.KEYCODE_F3:
                return K_F3;
            case KeyEvent.KEYCODE_F4:
                return K_F4;
            case KeyEvent.KEYCODE_F5:
                return K_F5;
            case KeyEvent.KEYCODE_F6:
                return K_F6;
            case KeyEvent.KEYCODE_F7:
                return K_F7;
            case KeyEvent.KEYCODE_F8:
                return K_F8;
            case KeyEvent.KEYCODE_F9:
                return K_F9;
            case KeyEvent.KEYCODE_F10:
                return K_F10;
            case KeyEvent.KEYCODE_F11:
                return K_F11;
            case KeyEvent.KEYCODE_F12:
                return K_F12;
            case KeyEvent.KEYCODE_CAPS_LOCK:
                return K_CAPSLOCK;
            case KeyEvent.KEYCODE_PAGE_DOWN:
                return K_PGDN;
            case KeyEvent.KEYCODE_PAGE_UP:
                return K_PGUP;
            case KeyEvent.KEYCODE_BUTTON_A:
                return K_ENTER;
            case KeyEvent.KEYCODE_BUTTON_B:
                return K_MOUSE1;
            case KeyEvent.KEYCODE_BUTTON_X:
                return '#'; //prev weapon, set in the config.txt as impulse 12
            case KeyEvent.KEYCODE_BUTTON_Y:
                return '/';//Next weapon, set in the config.txt as impulse 10
            //These buttons are not so popular
            case KeyEvent.KEYCODE_BUTTON_C:
                return 'a';//That's why here is a, nobody cares.
            case KeyEvent.KEYCODE_BUTTON_Z:
                return 'z';
            //--------------------------------
            case KeyEvent.KEYCODE_BUTTON_START:
                return K_ESCAPE;
            case KeyEvent.KEYCODE_BUTTON_SELECT:
                return K_ENTER;
            case KeyEvent.KEYCODE_MENU:
                return K_ESCAPE;

            //Both shoulder buttons will "fire"
            case KeyEvent.KEYCODE_BUTTON_R1:
            case KeyEvent.KEYCODE_BUTTON_R2:
                return K_MOUSE1;

            //enables "run"
            case KeyEvent.KEYCODE_BUTTON_L1:
            case KeyEvent.KEYCODE_BUTTON_L2:
                return K_SHIFT;
            case KeyEvent.KEYCODE_BUTTON_THUMBL:
                return -1;
        }
        int uchar = event.getUnicodeChar(0);
        if((uchar < 127)&&(uchar!=0))
            return uchar;
        return keyCode%95+32;//Magic
    }


    public void SetupUVCoords()
    {
        // The texture buffer
        ByteBuffer bb = ByteBuffer.allocateDirect(uvs.length * 4);
        bb.order(ByteOrder.nativeOrder());
        uvBuffer = bb.asFloatBuffer();
        uvBuffer.put(uvs);
        uvBuffer.position(0);
    }

    public void SetupTriangle(int x, int y, int width, int height)
    {
        // We have to create the vertices of our triangle.
        vertices = new float[]
                {
                        x, y + height, 0.0f,
                        x, y, 0.0f,
                        x + width, y, 0.0f,
                        x + width, y + height, 0.0f,
                };

        // The vertex buffer.
        ByteBuffer bb = ByteBuffer.allocateDirect(vertices.length * 4);
        bb.order(ByteOrder.nativeOrder());
        vertexBuffer = bb.asFloatBuffer();
        vertexBuffer.put(vertices);
        vertexBuffer.position(0);
    }

    public void SwitchVRMode() {
        mVRMode = (mVRMode + 1) % 3;
        SwitchVRMode(mVRMode);
    }

    @Override
    public void SwitchVRMode(int vrMode) {
        mVRMode = vrMode;
        if (mVRMode == 0) {
            cardboardView.setVRModeEnabled(false);
            mSurfaceChanged = false;
            sideBySide = false;
        }
        if (mVRMode == 1) {
            cardboardView.setVRModeEnabled(false);
            mSurfaceChanged = true;
            sideBySide = true;
        }
        if (mVRMode == 2) {
            cardboardView.setVRModeEnabled(true);
            mSurfaceChanged = false;
            sideBySide = false;
        }
        mVRModeChanged = true;
    }

    @Override
    public void Exit() {
        mAudio.terminateAudio();
        try {
            Thread.sleep(1000);
        }
        catch (InterruptedException ie){
        }
        System.exit(0);
    }


}
