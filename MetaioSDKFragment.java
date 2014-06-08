package com.metaio.Example;

import android.annotation.SuppressLint;
import android.app.Application;
import android.app.Fragment;
import android.content.res.Configuration;
import android.os.Bundle;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.ViewGroup.LayoutParams;

import com.metaio.sdk.MetaioDebug;
import com.metaio.sdk.MetaioSurfaceView;
import com.metaio.sdk.SensorsComponentAndroid;
import com.metaio.sdk.jni.Camera;
import com.metaio.sdk.jni.CameraVector;
import com.metaio.sdk.jni.ERENDER_SYSTEM;
import com.metaio.sdk.jni.ESCREEN_ROTATION;
import com.metaio.sdk.jni.IGeometry;
import com.metaio.sdk.jni.IMetaioSDKAndroid;
import com.metaio.sdk.jni.IMetaioSDKCallback;
import com.metaio.sdk.jni.MetaioSDK;
import com.metaio.tools.Screen;

@SuppressLint("NewApi")
public abstract class MetaioSDKFragment extends Fragment implements MetaioSurfaceView.Callback, OnTouchListener{

	/** アプリケーションコンテキスト */
	private Application mAppContext;

	/** 最上位のレイアウト（ここに） */
	protected ViewGroup mRootLayout;

	//--- Metaio関連 ---

	private IMetaioSDKCallback mCallback ;

	protected View mGUIView ;

	/** metaio SDK object */
	protected IMetaioSDKAndroid metaioSDK;

	/** metaio SurfaceView */
	private MetaioSurfaceView mSurfaceView;

	/** Metaioライブラリロードフラグ */
	private static boolean mNativeLibsLoaded = false;

	/** レンダリング初期化フラグ */
	private boolean mRendererInitialized;

	/** Sensor manager */
	private SensorsComponentAndroid mSensors;

	/** ネイティブライブラリを読み込む */
	static {
		mNativeLibsLoaded = IMetaioSDKAndroid.loadNativeLibs();
	}

	public abstract void loadContents() ;

	protected abstract int getGUILayout() ;

	protected abstract IMetaioSDKCallback getMetaioSDKCallbackHandler() ;

	protected abstract void onGeometryTouched(IGeometry geometry) ;

	@Override
	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		Log.d("LifeCycle", "onCreate");

		mAppContext = getActivity().getApplication();
		metaioSDK = null;
		mSurfaceView = null;
		mRendererInitialized = false;
		try {

			mCallback = getMetaioSDKCallbackHandler() ;

			if (!mNativeLibsLoaded){
				throw new Exception("Unsupported platform, failed to load the native libs");
			}

			// カメラのセンサーコンポーネント作成
			mSensors = new SensorsComponentAndroid(mAppContext);

			// metaioSDKのシグネチャーコード送信
			metaioSDK = MetaioSDK.CreateMetaioSDKAndroid(getActivity(), getResources().getString(R.string.metaioSDKSignature));
			metaioSDK.registerSensorsComponent(mSensors);

		} catch (Throwable e) {
			MetaioDebug.log(Log.ERROR, "ArCameraFragment.onCreate: failed to create or intialize metaio SDK: " + e.getMessage());
			return;
		}
	}

	@Override
	public void onStart() {
		super.onStart();
		Log.d("LifeCycle", "onStart");

		if(metaioSDK == null){
			return;
		}
		MetaioDebug.log("ArCameraFragment.onStart()");

		try {
			mSurfaceView = null;

			// Start camera
			startCamera();

			// サーフェイスビューを追加（オーバーレイ）
			mSurfaceView = new MetaioSurfaceView(mAppContext);
			mSurfaceView.registerCallback(this);
			mSurfaceView.setOnTouchListener(this);
			mSurfaceView.setKeepScreenOn(true);

			MetaioDebug.log("ArCameraFragment.onStart: addContentView(metaioSurfaceView)");
			mRootLayout.addView(mSurfaceView, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
					ViewGroup.LayoutParams.WRAP_CONTENT));
			mSurfaceView.setZOrderMediaOverlay(true);

			//GUIレイアウト再生
			final int layout = getGUILayout();
			if (layout != 0){
				mGUIView = View.inflate(mAppContext, layout, null);
				if (mGUIView == null)
					MetaioDebug.log(Log.ERROR, "ARViewActivity: error inflating the given layout: "+layout);
			}
			//GUIレイアウトが有れば作成
			if (mGUIView != null) {
				if (mGUIView.getParent() == null) {
					MetaioDebug.log("ARViewActivity.onResume: addContentView(mGUIView)");
					getActivity().addContentView(mGUIView, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
				}
				mGUIView.bringToFront();
			}
		} catch (Exception e) {
			MetaioDebug.log(Log.ERROR, "Error creating views: " + e.getMessage());
			MetaioDebug.printStackTrace(Log.ERROR, e);
		}
	}

	/**
	 * FragmentをResumeする.
	 * @see Fragment#onResume
	 */
	@Override
	public void onResume() {
		super.onResume();
		Log.d("LifeCycle", "onResume");

		//resume　the penGL surface
		if (mSurfaceView != null) {
			mSurfaceView.onResume();
		}

		if(metaioSDK != null){
			metaioSDK.resume();
		}
	}

	@Override
	public void onPause() {
		super.onPause();

		Log.d("LifeCycle", "onPause");

		// pause the OpenGL surface
		if (mSurfaceView != null) {
			mSurfaceView.onPause();
		}

		if (metaioSDK != null) {
			metaioSDK.pause();
		}

	}

	@Override
	public void onStop() {
		super.onStop();

		Log.d("LifeCycle", "onStop");

		if (metaioSDK != null) {
			// Disable the camera
			metaioSDK.stopCamera();
		}

		if (mSurfaceView != null) {
			mRootLayout.removeView(mSurfaceView);
		}

		System.runFinalization();
		System.gc();
	}

	@Override
	public void onDestroy() {
		super.onDestroy();

		Log.d("LifeCycle", "onDestroy");

		try {
			mRendererInitialized = false;
		} catch (Exception e) {
			MetaioDebug.printStackTrace(Log.ERROR, e);
		}

		MetaioDebug.log("ArCameraFragment.onDestroy");

		if (metaioSDK != null) {
			metaioSDK.delete();
			metaioSDK = null;
		}

		MetaioDebug.log("ArCameraFragment.onDestroy releasing sensors");
		if (mSensors != null) {
			mSensors.registerCallback(null);
			mSensors.release();
			mSensors.delete();
			mSensors = null;
		}

		// Memory.unbindViews(activity.findViewById(android.R.id.content));

		System.runFinalization();
		System.gc();
	}

	@Override
	public void onConfigurationChanged(Configuration newConfig) {
		super.onConfigurationChanged(newConfig);
		final ESCREEN_ROTATION rotation = Screen.getRotation(getActivity());
		metaioSDK.setScreenRotation(rotation);
		MetaioDebug.log("onConfigurationChanged: " + rotation);
	}

	@Override
	public void onDrawFrame() {
		//		Log.d("LifeCycle", "onDrawFrame");
		if (mRendererInitialized) {
			metaioSDK.render();
		}
	}

	@Override
	public void onSurfaceCreated() {
		Log.d("LifeCycle", "onSurfaceCreated");

		try {
			if (!mRendererInitialized) {
				metaioSDK.initializeRenderer(mSurfaceView.getWidth(), mSurfaceView.getHeight(), Screen.getRotation(getActivity()),
						ERENDER_SYSTEM.ERENDER_SYSTEM_OPENGL_ES_2_0);
				mRendererInitialized = true;
			} else {
				MetaioDebug.log("ArCameraFragment.onSurfaceCreated: Reloading textures...");
				metaioSDK.reloadOpenGLResources();
			}

			MetaioDebug.log("ArCameraFragment.onSurfaceCreated: Registering audio renderer...");
			metaioSDK.registerCallback(mCallback);

			MetaioDebug.log("ARViewActivity.onSurfaceCreated");
		} catch (Exception e) {
			MetaioDebug.log(Log.ERROR, "ArCameraFragment.onSurfaceCreated: " + e.getMessage());
		}

		// サーフェスビュー初期化が終わったらコンテンツをロード
		mSurfaceView.queueEvent(new Runnable() {
			@Override
			public void run() {
				loadContents();
			}
		});

	}

	@Override
	public void onSurfaceChanged(int width, int height) {
		Log.d("LifeCycle", "onSurfaceChanged");

		metaioSDK.resizeRenderer(width, height);
	}

	@Override
	public void onSurfaceDestroyed() {
		Log.d("LifeCycle", "onSurfaceDestroyed");

		MetaioDebug.log("ArCameraFragment.onSurfaceDestroyed(){");
		mSurfaceView = null;
	}

	@Override
	public boolean onTouch(View v, MotionEvent event) {
		if (event.getAction() == MotionEvent.ACTION_UP) {
			MetaioDebug.log("ARViewActivity touched at: "+event.toString());

			try
			{

				final int x = (int) event.getX();
				final int y = (int) event.getY();

				// ask the SDK if a geometry has been hit
				IGeometry geometry = metaioSDK.getGeometryFromViewportCoordinates(x, y, true);
				if (geometry != null) {
					MetaioDebug.log("ARViewActivity geometry found: "+geometry);
					onGeometryTouched(geometry);
				}

			}
			catch (Exception e)
			{
				MetaioDebug.log(Log.ERROR, "onTouch: "+e.getMessage());
				MetaioDebug.printStackTrace(Log.ERROR, e);
			}

		}
		return true;
	}


	/**
	 * カメラを起動する.
	 */
	protected void startCamera() {
		final CameraVector cameras = metaioSDK.getCameraList();
		if (!cameras.isEmpty()){
			com.metaio.sdk.jni.Camera camera = cameras.get(0);

			// Choose back facing camera
			for (int i=0; i<cameras.size(); i++){
				if (cameras.get(i).getFacing() == Camera.FACE_BACK){
					camera = cameras.get(i);
					break;
				}
			}

			metaioSDK.startCamera(camera);
		}
		else{
			MetaioDebug.log(Log.WARN, "No camera found on the device!");
		}
	}
}