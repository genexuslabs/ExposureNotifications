package com.genexus.exposurenotifications;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.os.Build;

import com.artech.android.ApiAuthorizationStatus;
import com.artech.application.MyApplication;
import com.artech.base.services.Services;
import com.artech.base.utils.ReflectionHelper;
import com.artech.base.utils.Strings;
import com.artech.layers.GxObjectFactory;
import com.genexus.GXBaseCollection;
import com.genexus.xml.GXXMLSerializable;
import com.google.android.apps.exposurenotification.activities.utils.ExposureNotificationPermissionHelper;
import com.google.android.apps.exposurenotification.common.StringUtils;
import com.google.android.apps.exposurenotification.nearby.ProvideDiagnosisKeysWorker;
import com.google.android.apps.exposurenotification.storage.ExposureNotificationStorage;
import com.google.android.apps.exposurenotification.storage.TokenEntity;
import com.google.android.apps.exposurenotification.storage.TokenRepository;
import com.google.android.gms.common.ConnectionResult;
import com.google.android.gms.common.GoogleApiAvailability;
import com.google.android.gms.nearby.exposurenotification.ExposureInformation;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

import json.org.json.JSONArray;

import static com.genexus.exposurenotifications.ExposureNotificationsAPI.METHOD_GET_LAST_DETAILS;
import static com.genexus.exposurenotifications.ExposureNotificationsAPI.PROPERTY_EXPOSURE_DETECTION_RESULT;
import static com.genexus.exposurenotifications.ExposureNotificationsAPI.PROPERTY_WAS_EXPOSURE_DETECTED;


public class ExposureNotificationsAPIOffline {

	private static int authorizationStatusValue = ApiAuthorizationStatus.NOTDETERMINATE;
	public static boolean sScheduleDaily = false;

	// properties
	@SuppressWarnings("deprecation")
	public static boolean isEnabled() {
		boolean enabled = isEnabledInternal();
		// check if should schedule sync?
		if (enabled) {
			Services.Log.debug(" isEnabled success , schedule sync server if not already do");
			boolean shouldRunSync = ExposureNotificationsAPIOffline.shouldRunSync();
			if (shouldRunSync && !sScheduleDaily)
			{
				Services.Log.debug(" isEnabled success , shouldRunSync and not  scheduleDaily. Do it");
				// schedule server api download,
				sScheduleDaily = true;
				ProvideDiagnosisKeysWorker.scheduleDailyProvideDiagnosisKeys(MyApplication.getInstance());
			}
		}
		return enabled;
	}

	protected static boolean isEnabledInternal() {
		Services.Log.debug("Check EN isEnabled");
		try {
			ListenableFuture<Boolean> listenableFuture = ExposureNotificationPermissionHelper.isEnabled(MyApplication.getAppContext());
			boolean enabled = listenableFuture.get();
			if (enabled)
				authorizationStatusValue = ApiAuthorizationStatus.AUTHORIZED;
			else {
				if (isAvailable()) {
					if (ExposureNotificationStorage.getStartCalled())
						authorizationStatusValue = ApiAuthorizationStatus.DENIED;
				}
				else
					authorizationStatusValue = ApiAuthorizationStatus.RESTRICTED;
			}
			Services.Log.debug("EN isEnabled return " + enabled);
			return enabled;
		} catch (ExecutionException | InterruptedException ex) {
			Services.Log.error("Error query ExposureNotificationsAPI enabled");
			Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
			// TODO: do something with the exception?
			return false;
		}
		catch (NoClassDefFoundError ex) {
			// this should be handle by isAvailable, but is not working in all cases. return false
			Services.Log.error("Error query ExposureNotificationsAPI enabled NoClassDefFoundError");
			Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
			return false;
		}
	}

	public static boolean isAvailable() {
		// check android version
		if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M)  {  // API 23 or higher
			authorizationStatusValue = ApiAuthorizationStatus.RESTRICTED;
			Services.Log.debug("Android version lower than 6.x");
			return false;
		}
		// check Google Play Services
		if ( GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(MyApplication.getAppContext())!= ConnectionResult.SUCCESS ) {
			authorizationStatusValue = ApiAuthorizationStatus.RESTRICTED;
			Services.Log.debug("Google Play services not available");
			return false;
		}
		Services.Log.debug("Google Play services version: "+ GoogleApiAvailability.getInstance().getClientVersion(MyApplication.getAppContext()) );
		// check for BLE, already do it in the manifest.
		// check nearby available
		if (ExposureNotificationsAPIHelper.hasExposureNotificationModule(MyApplication.getAppContext()))
			Services.Log.info(" ExposureNotification Module Enabled!");
		else {
			Services.Log.error(" ExposureNotification Module NOT Enabled!");
			return false;
		}
		// check nearby classes available.
		try {
			Services.Log.debug("check nearby classes available.");
			ListenableFuture<Boolean> listenableFuture = ExposureNotificationPermissionHelper.isEnabled(MyApplication.getAppContext());
			Services.Log.debug("check nearby classes available done. " + listenableFuture.isDone());
		}
		catch (NoClassDefFoundError ex) {
			// this should be handle by isAvailable, but is not working in all cases. return false
			Services.Log.error("Error init ExposureNotificationsAPI NoClassDefFoundError");
			Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
			return false;
		}
		return true;
	}

	public static int authorizationStatus()
	{
		return authorizationStatusValue;
	}

	public static int getExposureDetectionMinInterval()
	{
		return ExposureNotificationStorage.getExposureDetectionMinInterval();
	}

	@SuppressWarnings("deprecation")
	public static void setExposureDetectionMinInterval(int exposureDetectionMinInterval)
	{
		if (exposureDetectionMinInterval>16) {
			int previousExposureDetectionMinInterval = getExposureDetectionMinInterval();
			ExposureNotificationStorage.setExposureDetectionMinInterval(exposureDetectionMinInterval);
			// raise schedule the daily job of scheduleDailyProvideDiagnosisKeys if time say to do .
			// schedule sync server API , if enabled true
			boolean enabled = isEnabledInternal();
			Services.Log.debug(" ExposureDetectionMinInterval changed , schedule sync server Enabled: " + enabled);
			if (enabled) {
				boolean shouldRunSync = shouldRunSync();
				if (shouldRunSync)
				{
					// schedule server api download,
					ExposureNotificationsAPIOffline.sScheduleDaily = true;
					Services.Log.debug(" ExposureDetectionMinInterval changed , scheduleDailyProvideDiagnosisKeys.");
					ProvideDiagnosisKeysWorker.scheduleDailyProvideDiagnosisKeys(MyApplication.getInstance());
				}
				else
				{
					// is exposure interval are diferents.
					if (previousExposureDetectionMinInterval != exposureDetectionMinInterval) {
						ExposureNotificationsAPIOffline.sScheduleDaily = true;
						Services.Log.debug(" ExposureDetectionMinInterval changed , scheduleDailyProvideDiagnosisKeys. new interval: " + exposureDetectionMinInterval);
						ProvideDiagnosisKeysWorker.scheduleDailyProvideDiagnosisKeys(MyApplication.getInstance());
					}
				}
			}
		}
		else {
			Services.Log.error("cannot set setExposureDetectionMinInterval to under 17");
		}
	}

	public static boolean shouldRunSync() {
		long minTimeBetweenSync = ExposureNotificationStorage.getExposureDetectionMinInterval(); // in minutes
		long nowTime = new Date().getTime();
		long lastSync = ExposureNotificationStorage.getLastPerformedExposureDetectionTimeStamp();
		boolean shouldRunSync = true;

		if (lastSync != 0 && ((nowTime - lastSync) < (minTimeBetweenSync * 1000 * 60))) {
			shouldRunSync = false;
			Services.Log.debug("MinTimeBetweenSync time not happened yet, do nothing.");
		}
		return shouldRunSync;
	}

	public static String getExposureInformationUserExplanation()
	{
		return ExposureNotificationStorage.getExposureInformationUserExplanation();
	}

	public static void setExposureInformationUserExplanation(String userExplanation)
	{
		ExposureNotificationStorage.setExposureInformationUserExplanation(userExplanation);
		// TODO: have to use userExplanation somewhere ?.
	}

	public static boolean isExposureDetected()
	{
		TokenEntity tokenEntity = getLastToken();

		if (tokenEntity==null) {
			Services.Log.debug("isExposureDetected , token null , no exposure detected ");
			return false; // no exposure detected
		}
		// Get Summary from token.
		ListenableFuture<ExposureSummary> listenableFutureSummary = ExposureNotificationsAPIHelper.getExposureSummary(MyApplication.getAppContext(), tokenEntity.getToken());
		ExposureSummary exposureSummary = null;
		try {
			// Call to know exposure detection sumarry
			Services.Log.debug("method " + PROPERTY_WAS_EXPOSURE_DETECTED + " called for exposure summary");
			exposureSummary = listenableFutureSummary.get();

		} catch (ExecutionException | InterruptedException ex) {
			Services.Log.error("Error query " + PROPERTY_WAS_EXPOSURE_DETECTED);
			Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
			return false;
		}
		Services.Log.debug(" Maching keys : " + exposureSummary.getMatchedKeyCount());
		if (exposureSummary.getMatchedKeyCount()>0)
			return true;
		else
			return false;
	}

	public static Object getExposureDetectedSummary()
	{
		String classTypeName = "exposurealerts.SdtExposureDetectionSessionResult";
		String fullClassName = GxObjectFactory.addAppPackageNameToClass(classTypeName);
		Class<?> clazz = ReflectionHelper.getClass(Object.class, fullClassName);
		if (clazz == null)
			throw new IllegalStateException("SdtExposureDetectionSessionResult class could not be loaded!");

		Object obj = ReflectionHelper.createDefaultInstance(clazz, true);

		TokenEntity tokenEntity = ExposureNotificationsAPIOffline.getLastToken();

		if (tokenEntity==null) {
			Services.Log.debug("getExposureDetectedSummary , token null , summary empty ");
			return obj; // return empty sdt
		}
		// Get Summary from token.
		ListenableFuture<ExposureSummary> listenableFutureSummary = ExposureNotificationsAPIHelper.getExposureSummary(MyApplication.getAppContext(), tokenEntity.getToken());
		ExposureSummary exposureSummary = null;

		try {
			// Call only for exposure detection summary
			Services.Log.debug("method " + PROPERTY_EXPOSURE_DETECTION_RESULT + " called for exposure summary");
			exposureSummary = listenableFutureSummary.get();

		} catch (ExecutionException | InterruptedException ex) {
			Services.Log.error("Error query " + PROPERTY_EXPOSURE_DETECTION_RESULT);
			Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
			return obj; // return empty sdt
		}

		// convert ExposureSummary to SDT for Gx format
		json.org.json.JSONObject jsonExposureDetectionSessionResult = new json.org.json.JSONObject();
		try {
			jsonExposureDetectionSessionResult.put("Id", tokenEntity.getToken());
			Date dateSession = new Date(tokenEntity.getLastUpdatedTimestampMs());
			jsonExposureDetectionSessionResult.put("SessionTimeStamp", Services.Strings.getDateTimeStringForServer(dateSession)	);
			Date dateDaysSince = new Date();
			Calendar c = Calendar.getInstance();
			c.setTime(dateDaysSince);
			c.add(Calendar.DATE, exposureSummary.getDaysSinceLastExposure()*-1);
			jsonExposureDetectionSessionResult.put("LastExposureDate", Services.Strings.getDateStringForServer(c.getTime()) );
			jsonExposureDetectionSessionResult.put("MatchedKeyCount", exposureSummary.getMatchedKeyCount());
			jsonExposureDetectionSessionResult.put("MaximumRiskScore", exposureSummary.getMaximumRiskScore());
			String arrayValues = Arrays.toString(exposureSummary.getAttenuationDurationsInMinutes());
			if (Strings.hasValue(arrayValues))
				jsonExposureDetectionSessionResult.put("AttenuationDurations", new JSONArray(arrayValues));

		} catch (json.org.json.JSONException e) {
			Services.Log.error("Error creating JSON for ExposureDetectionSessionResult", "Exception in JSONObject.put()", e);
		}

		Services.Log.debug("getExposureDetectedSummary result " + jsonExposureDetectionSessionResult.toString());
		((GXXMLSerializable)obj).fromJSonString(jsonExposureDetectionSessionResult.toString());

		return obj;
	}

	private static List<TokenEntity> getTokens()
	{
		TokenRepository tokenRepository = new TokenRepository(MyApplication.getAppContext());
		ListenableFuture<List<TokenEntity>> listenableFuture = tokenRepository.getAllAsync();
		List<TokenEntity> tokenEntities = null;
		try {
			tokenEntities = listenableFuture.get();
		} catch (ExecutionException | InterruptedException ex) {
			Services.Log.error("Error gettings tokens " + " getAllAsync " + ex.getMessage());
		}
		return tokenEntities;
	}

	public static TokenEntity getLastToken() {
		List<TokenEntity> tokenEntities = getTokens();

		if (tokenEntities == null)
			return null; // return empty string

		for (TokenEntity tokenEntity : tokenEntities) {
			return tokenEntity;
		}
		return null;
	}

	public static TokenEntity getLastRespondedToken() {
		List<TokenEntity> tokenEntities = getTokens();

		if (tokenEntities == null)
			return null; // return empty string

		for (TokenEntity tokenEntity : tokenEntities) {
			if (tokenEntity.isResponded()) {
				return tokenEntity;
			}
		}
		return null;
	}

	//methods
	// for use from offline code.

	// return List<ExposureInformation>
	public static GXBaseCollection getLastDetails()
	{
		String classTypeName = "exposurealerts.SdtExposureInfo";
		String fullClassName = GxObjectFactory.addAppPackageNameToClass(classTypeName);
		Class clazzType = ReflectionHelper.getClass(Object.class, fullClassName);
		if (clazzType == null)
		{
			Services.Log.error("SdtTemporaryExposureKey not found ");
			return null;
		}

		//noinspection unchecked
		@SuppressWarnings("unchecked")
		GXBaseCollection base = new GXBaseCollection(clazzType, "ExposureInfo", "ExposureAlerts", MyApplication.getApp().getRemoteHandle() );

		// get last token
		TokenEntity tokenEntity = getLastToken();

		if (tokenEntity==null) {
			Services.Log.debug("getLastDetails , token null , details empty ");
			return base; // return empty collection
		}

		ListenableFuture<List<ExposureInformation>> listenableFuture = ExposureNotificationsAPIHelper.getExposureInformation(MyApplication.getAppContext(), tokenEntity.getToken());
		List<ExposureInformation> exposureInfoList = null;
		try {
			// Call for exposure details
			Services.Log.debug("method " + METHOD_GET_LAST_DETAILS + " called for exposure details");
			exposureInfoList = listenableFuture.get();

		} catch (ExecutionException | InterruptedException ex) {
			Services.Log.error("Error query " + METHOD_GET_LAST_DETAILS);
			Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
			return base;
		}

		// convert to GxBaseCollection to return

		json.org.json.JSONArray jsonexposureInfos = new json.org.json.JSONArray();
		try {
			for (ExposureInformation einfo : exposureInfoList)
			{
				json.org.json.JSONObject jsonExposureInfo = new json.org.json.JSONObject();

				String formattedDate = StringUtils.epochTimestampToServerDateString(einfo.getDateMillisSinceEpoch());
				jsonExposureInfo.put("Date", formattedDate);
				jsonExposureInfo.put("Duration", einfo.getDurationMinutes());
				jsonExposureInfo.put("TransmissionRiskLevel", einfo.getTransmissionRiskLevel());
				jsonExposureInfo.put("TotalRiskScore", einfo.getTotalRiskScore());
				jsonExposureInfo.put("AttenuationValue", einfo.getAttenuationValue());
				String arrayValues = Arrays.toString(einfo.getAttenuationDurationsInMinutes());
				if (Strings.hasValue(arrayValues))
					jsonExposureInfo.put("AttenuationDurations", new JSONArray(arrayValues));

				jsonexposureInfos.put(jsonExposureInfo);
			}
		} catch (json.org.json.JSONException ex) {
			Services.Log.error("Json Exception " + ex.getMessage());
		}
		Services.Log.debug("getLastDetails result " + jsonexposureInfos.toString());
		base.fromJSonString(jsonexposureInfos.toString());

		return base;
	}

	// resetCollectedExposureData return boolean
	public static boolean resetExposureDetectionData()
	{
		// clear all tokens data
		TokenRepository tokenRepository = new TokenRepository(MyApplication.getAppContext());
		ListenableFuture<Void> listenableFuture = tokenRepository.deleteAllTokensAsync();
		try {
			listenableFuture.get();
		} catch (ExecutionException | InterruptedException ex) {
			Services.Log.error("Error removing tokens " + "deleteAllTokensAsync");
			return false;
		}
		Services.Log.debug("resetExposureDetectionData delete all tokens ");
		return true;
	}

	// bluetooth management.
	public static boolean isBluetoothEnabled()
	{
		// Initializes Bluetooth adapter.
		final BluetoothManager bluetoothManager =
			(BluetoothManager) MyApplication.getAppContext().getSystemService(Context.BLUETOOTH_SERVICE);
		if (bluetoothManager!=null) {
			BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
			Services.Log.debug("Check bluetooth settings bluetoothAdapter");
			// Ensures Bluetooth is available on the device and it is enabled. If not,
			// displays a dialog requesting user permission to enable Bluetooth.
			if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
				Services.Log.debug("bluetoothAdapter NOT Enabled");
				return false;
			}
		}
		// default, do not know return true
		Services.Log.debug("bluetoothAdapter Enabled");
		return true;
	}



	/*
	not working , need UI resolution. keep just if needed later
	 */
	/*
	public static GXBaseCollection getTemporaryExposureKeyHistoryForSharing()
	{
		String classTypeName = "ExposureAlerts.SdtTemporaryExposureKey";
		Class clazzType = ReflectionHelper.getClass(Object.class, classTypeName);
		if (clazzType == null)
		{
			Services.Log.error("SdtTemporaryExposureKey not found ");
			return null;
		}

		//noinspection unchecked
		@SuppressWarnings("unchecked")
		GXBaseCollection base = new GXBaseCollection(clazzType, "TemporaryExposureKey", "ExposureAlerts", MyApplication.getApp().getRemoteHandle() );

		ListenableFuture<List<TemporaryExposureKey>> listenableFuture = ExposureNotificationsAPIHelper.getTemporaryExposureKeyHistory(ActivityHelper.getCurrentActivity());
		List<TemporaryExposureKey> temporaryExposureKeys = null;
		try {
			// Call only for positive user
			Services.Log.debug("method " + METHOD_GET_TEMP_KEY_SHARING + " called for positive ids");
			temporaryExposureKeys = listenableFuture.get();

		} catch (ExecutionException | InterruptedException ex) {
			Services.Log.error("Error query " + METHOD_GET_TEMP_KEY_SHARING);
			Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
			return base;
		}

		json.org.json.JSONArray jsonTempKeys = new json.org.json.JSONArray();
		try {
			for (TemporaryExposureKey tek : temporaryExposureKeys)
			{
				json.org.json.JSONObject jsonTempKey = new json.org.json.JSONObject();
				jsonTempKey.put("KeyData", ExposureNotificationsAPI.BASE64_LOWER.encode(tek.getKeyData()));
				jsonTempKey.put("RollingStartIntervalNumber", tek.getRollingStartIntervalNumber());
				jsonTempKey.put("RollingPeriod", tek.getRollingPeriod());
				jsonTempKey.put("TransmissionRiskLevel", tek.getTransmissionRiskLevel());

				jsonTempKeys.put(jsonTempKey);
			}
		} catch (json.org.json.JSONException ex) {
			Services.Log.error("Json Exception " + ex.getMessage());
		}
		base.fromJSonString(jsonTempKeys.toString());

		return base;
	}
	 */
}
