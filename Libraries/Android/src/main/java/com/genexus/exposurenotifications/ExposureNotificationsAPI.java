package com.genexus.exposurenotifications;

import android.app.Activity;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothManager;
import android.content.Context;
import android.content.Intent;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.work.OneTimeWorkRequest;
import androidx.work.WorkManager;

import com.artech.actions.ActionExecution;
import com.artech.actions.ApiAction;
import com.artech.activities.ActivityHelper;
import com.artech.android.ContextImpl;
import com.artech.application.MyApplication;
import com.artech.base.application.IGxObject;
import com.artech.base.application.OutputResult;
import com.artech.base.metadata.enums.Connectivity;
import com.artech.base.metadata.expressions.Expression;
import com.artech.base.metadata.loader.ApplicationLoader;
import com.artech.base.metadata.loader.LoadResult;
import com.artech.base.metadata.loader.MetadataLoader;
import com.artech.base.model.Entity;
import com.artech.base.model.EntityFactory;
import com.artech.base.model.EntityList;
import com.artech.base.model.PropertiesObject;
import com.artech.base.providers.IApplicationServer;
import com.artech.base.services.Services;
import com.artech.externalapi.ExternalApi;
import com.artech.externalapi.ExternalApiResult;
import com.google.android.apps.exposurenotification.activities.utils.ExposureNotificationPermissionHelper;
import com.google.android.apps.exposurenotification.common.StringUtils;
import com.google.android.apps.exposurenotification.nearby.ProvideDiagnosisKeysWorker;
import com.google.android.apps.exposurenotification.storage.ExposureNotificationStorage;
import com.google.android.apps.exposurenotification.storage.TokenEntity;
import com.google.android.apps.exposurenotification.storage.TokenRepository;
import com.google.android.gms.nearby.exposurenotification.ExposureConfiguration;
import com.google.android.gms.nearby.exposurenotification.ExposureInformation;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.concurrent.ExecutionException;

import json.org.json.JSONArray;
import json.org.json.JSONException;
import json.org.json.JSONObject;

import static com.google.android.apps.exposurenotification.activities.utils.RequestCodes.REQUEST_CODE_GET_TEMP_EXPOSURE_KEY_HISTORY;
import static com.google.android.apps.exposurenotification.activities.utils.RequestCodes.REQUEST_CODE_START_EXPOSURE_NOTIFICATION;

public class ExposureNotificationsAPI extends ExternalApi {

	public static final String OBJECT_NAME = "ExposureAlerts.ExposureNotification";

	// Properties
	private static final String PROPERTY_IS_AVAILABLE = "IsAvailable"; // boolean
	private static final String PROPERTY_IS_ENABLED = "Enabled"; // boolean
	private static final String PROPERTY_AUTHORIZATION_STATUS = "AuthorizationStatus"; //APIAuthorizationStatus

	public static final String PROPERTY_EXPOSURE_DETECTION_RESULT = "LastExposureDetectionResult"; //collection of ExposureDetectionSessionResult
	public static final String PROPERTY_WAS_EXPOSURE_DETECTED = "ExposureDetected"; //boolean
	private static final String PROPERTY_EXPOSURE_DETECTION_MIN_INTERVAL = "ExposureDetectionMinInterval"; //numeric read-write
	private static final String PROPERTY_EXPOSURE_INFO_EXPLANATION = "ExposureInformationUserExplanation"; //varchar read-write

	private static final String PROPERTY_BLUETOOTH_ENABLED = "BluetoothEnabled"; //boolean

	// Methods
	private static final String METHOD_START = "Start"; //boolean
	private static final String METHOD_STOP = "Stop"; //boolean

	public static final String METHOD_GET_TEMP_KEY_SHARING = "GetTemporaryExposureKeyHistoryForSharing"; //collection of TemporaryExposureKey
	public static final String METHOD_GET_LAST_DETAILS = "GetLastExposureDetectionSessionDetails"; // collection of ExposureInfo
	private static final String METHOD_RESET_EXPOSURE_DATA = "ResetLastExposureDetectionResult"; // delete local token

	private static final String METHOD_SHOW_BLUETOOH_SETTINGS = "ShowBluetoothSettings"; // show bluetooh settings
	public static final int REQUEST_ENABLE_BT = 2323;

	// Events
	public static final String EVENT_EXPOSURE_DETECTED = "OnExposureDetected"; //

	private static final String PROPERTY_KEYSDP = "ExposureAlerts_ExposureNotificationDiagnosisKeysProvider";

	private final ExposureNotificationPermissionHelper permissionHelper;
	public static final BaseEncoding BASE64_LOWER = BaseEncoding.base64();
	private final TemporaryExposureKeyHistoryPermissionHelper tempExposurePermissionHelper;

	public ExposureNotificationsAPI(ApiAction action) {
		super(action);

		// helpers
		permissionHelper = new ExposureNotificationPermissionHelper(getActivity(), permissionHelperCallback);
		tempExposurePermissionHelper = new TemporaryExposureKeyHistoryPermissionHelper(getActivity(), tempExposurePermissionHelperCallback);

		//properties
		addReadonlyPropertyHandler(PROPERTY_IS_AVAILABLE, mGetIsAvailable );
		addReadonlyPropertyHandler(PROPERTY_IS_ENABLED, mGetEnabled );
		addReadonlyPropertyHandler(PROPERTY_AUTHORIZATION_STATUS, mGetAuthorizationStatus );
		addPropertyHandler(PROPERTY_EXPOSURE_DETECTION_MIN_INTERVAL, mGetExposureDetectionInterval, mSetExposureDetectionInterval);
		addPropertyHandler(PROPERTY_EXPOSURE_INFO_EXPLANATION, mGetExposureInformationUserExplanation, mSetExposureInformationUserExplanation);
		addReadonlyPropertyHandler(PROPERTY_EXPOSURE_DETECTION_RESULT, mGetExposureDetectionResult );
		addReadonlyPropertyHandler(PROPERTY_WAS_EXPOSURE_DETECTED, mGetExposureDetected );
		addReadonlyPropertyHandler(PROPERTY_BLUETOOTH_ENABLED, mGetBluetoothEnabled );

		//methods
		addMethodHandler(METHOD_START, 0, mMethodStart);
		addMethodHandler(METHOD_START, 1, mMethodStart); // parameter as ExposureConfiguration
		addMethodHandler(METHOD_STOP, 0, mMethodStop);
		addMethodHandler(METHOD_GET_TEMP_KEY_SHARING, 0, mMethodGetTempKeySharing);  // return Collection<TemporaryExposureKey>
		addMethodHandler(METHOD_GET_LAST_DETAILS, 0, mMethodGetLastDetails);  // return Collection<ExposureInformation>
		addMethodHandler(METHOD_RESET_EXPOSURE_DATA, 0, mMethodResetDetectionData);
		addMethodHandler(METHOD_SHOW_BLUETOOH_SETTINGS, 0, mMethodShowBluetoohSettings);

		//test
		addMethodHandler("test", 0, mMethodTest);
		addMethodHandler("StartDetectionSession", 0, mMethodStartDetectionSession);
		//test
	}

	//properties
	private final IMethodInvoker mGetEnabled =
		parameters -> ExternalApiResult.success(ExposureNotificationsAPIOffline.isEnabled());

	private final IMethodInvoker mGetIsAvailable =
		parameters -> ExternalApiResult.success(ExposureNotificationsAPIOffline.isAvailable());

	private final IMethodInvoker mGetAuthorizationStatus =
		parameters -> ExternalApiResult.success(ExposureNotificationsAPIOffline.authorizationStatus());

	private final IMethodInvoker mGetExposureDetectionInterval =
		parameters -> ExternalApiResult.success(ExposureNotificationsAPIOffline.getExposureDetectionMinInterval());

	private final IMethodInvoker mSetExposureDetectionInterval = parameters -> {
			int minInterval = Services.Strings.tryParseInt(parameters.get(0).toString(), 1440);
			ExposureNotificationsAPIOffline.setExposureDetectionMinInterval(minInterval);
			return ExternalApiResult.SUCCESS_CONTINUE;
		};

	private final IMethodInvoker mGetExposureInformationUserExplanation =
		parameters -> ExternalApiResult.success(ExposureNotificationsAPIOffline.getExposureInformationUserExplanation());

	private final IMethodInvoker mSetExposureInformationUserExplanation = parameters -> {
		String userExplanation = parameters.get(0).toString();
		ExposureNotificationsAPIOffline.setExposureInformationUserExplanation(userExplanation);
		return ExternalApiResult.SUCCESS_CONTINUE;
	};



	private final IMethodInvoker mGetExposureDetectionResult = new IMethodInvoker() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {
			Entity exposureSummaryEntity = EntityFactory.newSdt("ExposureAlerts.ExposureDetectionSessionResult");

			TokenEntity tokenEntity = ExposureNotificationsAPIOffline.getLastToken();

			if (tokenEntity==null) {
				Services.Log.debug("GetExposureDetectionResult , token null , summary empty ");
				return ExternalApiResult.success(exposureSummaryEntity); // return empty sdt
			}
			// Get Summary from token.
			ListenableFuture<ExposureSummary> listenableFutureSummary = ExposureNotificationsAPIHelper.getExposureSummary(getActivity(), tokenEntity.getToken());
			ExposureSummary exposureSummary = null;

			try {
				// Call only for exposure detection summary
				Services.Log.debug("method " + PROPERTY_EXPOSURE_DETECTION_RESULT + " called for exposure summary");
				exposureSummary = listenableFutureSummary.get();

			} catch (ExecutionException | InterruptedException ex) {
				Services.Log.error("Error query " + PROPERTY_EXPOSURE_DETECTION_RESULT);
				Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
				return ExternalApiResult.success(exposureSummaryEntity); // return empty sdt
			}

			// convert ExposureSummary to SDT for Gx format
			copyExposureSummaryToEntity(exposureSummary, exposureSummaryEntity, tokenEntity);
			Services.Log.debug("GetExposureDetectionResult , return summary " + exposureSummary.toString());
			return ExternalApiResult.success(exposureSummaryEntity);
		}
	};



	private static void copyExposureSummaryToEntity(ExposureSummary esummary, Entity exposureInfoEntity, TokenEntity tokenEntity)
	{
		exposureInfoEntity.setProperty("Id", tokenEntity.getToken());
		Date dateSession = new Date(tokenEntity.getLastUpdatedTimestampMs());
		exposureInfoEntity.setProperty("SessionTimeStamp", Services.Strings.getDateTimeStringForServer(dateSession));

		Date dateDaysSince = new Date();
		Calendar c = Calendar.getInstance();
		c.setTime(dateDaysSince);
		c.add(Calendar.DATE, esummary.getDaysSinceLastExposure()*-1);
		exposureInfoEntity.setProperty("LastExposureDate", Services.Strings.getDateStringForServer(c.getTime()));
		exposureInfoEntity.setProperty("MatchedKeyCount", esummary.getMatchedKeyCount());
		exposureInfoEntity.setProperty("MaximumRiskScore", esummary.getMaximumRiskScore());
		// TODO: add array of duration in minutes, see if works
		// array of ints[] esummary.getAttenuationDurationsInMinutes()
		exposureInfoEntity.setProperty("AttenuationDurations", Arrays.toString(esummary.getAttenuationDurationsInMinutes()) );

	}

	private final IMethodInvoker mGetExposureDetected = new IMethodInvoker() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {
			boolean exposureDetected = ExposureNotificationsAPIOffline.isExposureDetected();
			return ExternalApiResult.success(exposureDetected);
		}
	};

	private final IMethodInvoker mGetBluetoothEnabled = new IMethodInvoker() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {
			boolean bluetoothEnabled = ExposureNotificationsAPIOffline.isBluetoothEnabled();
			return ExternalApiResult.success(bluetoothEnabled);
		}
	};

	//methods
	private final IMethodInvoker mMethodStart = new IMethodInvokerWithActivityResult() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {
			if (parameters.size()>0)
			{
				Entity exposureConfiguration = (Entity) parameters.get(0);
				ExposureNotificationStorage.setExposureConfiguration(exposureConfiguration);
			}
			ExposureNotificationStorage.setStartCalled(true);
			permissionHelper.optInAndStartExposureTracing(getActivity());
			ActivityHelper.registerActionRequestCode(REQUEST_CODE_START_EXPOSURE_NOTIFICATION);
			return ExternalApiResult.SUCCESS_WAIT;
		}

		@NonNull
		@Override
		public ExternalApiResult handleActivityResult(int requestCode, int resultCode, @Nullable Intent result) {
			if (REQUEST_CODE_START_EXPOSURE_NOTIFICATION == requestCode) {
				permissionHelper.onResolutionComplete(requestCode, resultCode, getActivity());
				return ExternalApiResult.SUCCESS_WAIT;
			}

			return ExternalApiResult.SUCCESS_CONTINUE;
		}
	};

	private final IMethodInvoker mMethodStop = new IMethodInvoker() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {
			permissionHelper.optOut(getActivity());
			return ExternalApiResult.SUCCESS_WAIT;
		}

	};

	private final IMethodInvoker mMethodGetTempKeySharing = new IMethodInvokerWithActivityResult() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {
			List<TemporaryExposureKey> temporaryExposureKeys = tempExposurePermissionHelper.optInAndGetTemporaryExposureKeyHistory(getActivity());
			if (temporaryExposureKeys!=null)
			{
				EntityList temporaryExposureKeysEntityList = convertTempKeyToEntityList(temporaryExposureKeys);

				Services.Log.debug("GetTempKeySharing , return temp key " + temporaryExposureKeys.toString());
				// Exposure keys List send to gx EO
				return ExternalApiResult.success(temporaryExposureKeysEntityList);
			}
			ActivityHelper.registerActionRequestCode(REQUEST_CODE_GET_TEMP_EXPOSURE_KEY_HISTORY);
			return ExternalApiResult.SUCCESS_WAIT;

		}

		@NonNull
		@Override
		public ExternalApiResult handleActivityResult(int requestCode, int resultCode, @Nullable Intent result) {
			if (REQUEST_CODE_GET_TEMP_EXPOSURE_KEY_HISTORY == requestCode) {
				tempExposurePermissionHelper.onResolutionComplete(requestCode, resultCode, getActivity());
				return ExternalApiResult.SUCCESS_WAIT;
			}
			return ExternalApiResult.SUCCESS_CONTINUE;
		}
	};

	private EntityList convertTempKeyToEntityList(List<TemporaryExposureKey> temporaryExposureKeys) {
		// convert temporaryExposureKeys to SDT for Gx format
		EntityList temporaryExposureKeysEntityList = new EntityList();

		for (TemporaryExposureKey tek : temporaryExposureKeys)
		{
			Entity temporaryExposureKeyEntity = EntityFactory.newSdt("ExposureAlerts.TemporaryExposureKey");
			copyExposureKeyToEntity(tek, temporaryExposureKeyEntity);
			temporaryExposureKeysEntityList.add(temporaryExposureKeyEntity);
		}
		return temporaryExposureKeysEntityList;
	}

	private static void copyExposureKeyToEntity(TemporaryExposureKey tek, Entity temporaryExposureKeyEntity)
	{
		temporaryExposureKeyEntity.setProperty("KeyData", BASE64_LOWER.encode(tek.getKeyData()));
		temporaryExposureKeyEntity.setProperty("RollingStartIntervalNumber", tek.getRollingStartIntervalNumber());
		// Fix GPS bug
		int rollingPeriod = tek.getRollingPeriod();
		if (rollingPeriod==0)
			rollingPeriod=144;
		temporaryExposureKeyEntity.setProperty("RollingPeriod", rollingPeriod);
		temporaryExposureKeyEntity.setProperty("TransmissionRiskLevel", tek.getTransmissionRiskLevel());
		Services.Log.debug("Temp Key: " + temporaryExposureKeyEntity.toDebugString());
	}

	private final IMethodInvoker mMethodGetLastDetails = new IMethodInvoker() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {
			// get last token
			TokenEntity tokenEntity = ExposureNotificationsAPIOffline.getLastToken();
			EntityList exposureInfoEntityList = new EntityList();

			if (tokenEntity==null) {
				Services.Log.debug("GetLastDetails , token null , detail empty ");
				return ExternalApiResult.success(exposureInfoEntityList); // return empty list
			}
			ListenableFuture<List<ExposureInformation>> listenableFuture = ExposureNotificationsAPIHelper.getExposureInformation(getActivity(), tokenEntity.getToken());
			List<ExposureInformation> exposureInfoList = null;
			try {
				// Call only for positive user
				Services.Log.debug("method " + METHOD_GET_LAST_DETAILS + " called for exposure details");
				exposureInfoList = listenableFuture.get();

			} catch (ExecutionException | InterruptedException ex) {
				Services.Log.error("Error query " + METHOD_GET_LAST_DETAILS);
				Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
				return ExternalApiResult.success(exposureInfoEntityList);
			}

			// convert ExposureInformation list to SDT for Gx format
			for (ExposureInformation einfo : exposureInfoList)
			{
				Entity exposureInfoEntity = EntityFactory.newSdt("ExposureAlerts.ExposureInfo");
				copyExposureInformationToEntity(einfo, exposureInfoEntity);
				exposureInfoEntityList.add(exposureInfoEntity);
			}
			if (exposureInfoList!=null)
				Services.Log.debug("GetLastDetails , return details " + exposureInfoList.toString());
			return ExternalApiResult.success(exposureInfoEntityList);

		}
	};

	private static void copyExposureInformationToEntity(ExposureInformation einfo, Entity exposureInfoEntity)
	{
		String formattedDate = StringUtils.epochTimestampToServerDateString(einfo.getDateMillisSinceEpoch());
		exposureInfoEntity.setProperty("Date", formattedDate);
		exposureInfoEntity.setProperty("Duration", einfo.getDurationMinutes());
		exposureInfoEntity.setProperty("TransmissionRiskLevel", einfo.getTransmissionRiskLevel());
		exposureInfoEntity.setProperty("TotalRiskScore", einfo.getTotalRiskScore());
		exposureInfoEntity.setProperty("AttenuationValue", einfo.getAttenuationValue());
		exposureInfoEntity.setProperty("AttenuationDurations", Arrays.toString(einfo.getAttenuationDurationsInMinutes()));

	}


	private final IMethodInvoker mMethodResetDetectionData = new IMethodInvoker() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {
			return ExternalApiResult.success(ExposureNotificationsAPIOffline.resetExposureDetectionData());
		}
	};

	public static ExposureConfiguration copyExposureConfigurationFromEntity()
	{
		// check app is loaded
		checkMetadata();

		Entity exposureConfigEntity = ExposureNotificationStorage.getExposureConfiguration();

		ExposureConfiguration.ExposureConfigurationBuilder builder = new ExposureConfiguration.ExposureConfigurationBuilder();
		try
		{
			builder.setMinimumRiskScore(Integer.parseInt(exposureConfigEntity.optStringProperty("MinimumRiskScore")));

			builder.setAttenuationWeight(Integer.parseInt(exposureConfigEntity.optStringProperty("AttenuationWeight")));
			builder.setDaysSinceLastExposureWeight(Integer.parseInt(exposureConfigEntity.optStringProperty("DaysSinceLastExposureWeight")));
			builder.setDurationWeight(Integer.parseInt(exposureConfigEntity.optStringProperty("DurationWeight")));
			builder.setTransmissionRiskWeight(Integer.parseInt(exposureConfigEntity.optStringProperty("TransmissionRiskWeight")));

			String attenuationScoresArrayString = exposureConfigEntity.optStringProperty("AttenuationScores");
			JSONArray arrayJsonInt = new JSONArray(attenuationScoresArrayString);
			int[] arrayInt = getIntArray(arrayJsonInt);
			builder.setAttenuationScores(arrayInt );

			String daysSinceLastExposureScoresArrayString = exposureConfigEntity.optStringProperty("DaysSinceLastExposureScores");
			arrayJsonInt = new JSONArray(daysSinceLastExposureScoresArrayString);
			arrayInt = getIntArray(arrayJsonInt);
			builder.setDaysSinceLastExposureScores(arrayInt );

			String durationScoresArrayString = exposureConfigEntity.optStringProperty("DurationScores");
			arrayJsonInt = new JSONArray(durationScoresArrayString);
			arrayInt = getIntArray(arrayJsonInt);
			builder.setDurationScores(arrayInt );

			String transmissionRiskScoresArrayString = exposureConfigEntity.optStringProperty("TransmissionRiskScores");
			arrayJsonInt = new JSONArray(transmissionRiskScoresArrayString);
			arrayInt = getIntArray(arrayJsonInt);
			builder.setTransmissionRiskScores(arrayInt );


		}
		catch (NumberFormatException | JSONException ex)
		{
			Services.Log.error(" Error creating ExposureConfiguration from our data " + ex.getMessage());
		}
		return builder.build();
	}

	private static void checkMetadata()
	{
		if (!Services.Application.isLoaded()) {
			Services.Log.debug("checkMetadata before fireApplicationEvent reload app");
			// try load metadata
			LoadResult loadResult;
			ApplicationLoader applicationLoader = new ApplicationLoader(MyApplication.getApp());
			try {
				// Load the Application.
				loadResult = applicationLoader.loadApplication(new ContextImpl(MyApplication.getAppContext()), MyApplication.getAppContext(), null);
			} catch (OutOfMemoryError ex) {
				// Notify the user that the app could not load correctly due to reduced memory.
				loadResult = LoadResult.error(ex);
			}
			Services.Log.debug("checkMetadata App metadata reload " + loadResult.getCode());
		}
	}

	private static int[] getIntArray(JSONArray arrayI) throws JSONException {
		int[] attenuationScoresArray = new int[arrayI.length()];
		for (int i=0; i<arrayI.length(); i++) {
			attenuationScoresArray[i] = arrayI.getInt(i);
		}
		return attenuationScoresArray;
	}

	private final IMethodInvoker mMethodShowBluetoohSettings = new IMethodInvokerWithActivityResult() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {
			final BluetoothManager bluetoothManager =
				(BluetoothManager) MyApplication.getAppContext().getSystemService(Context.BLUETOOTH_SERVICE);
			if (bluetoothManager!=null) {
				BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
				Services.Log.debug("Check bluetooth settings bluetoothAdapter");

				// Ensures Bluetooth is available on the device and it is enabled. If not,
				// displays a dialog requesting user permission to enable Bluetooth.
				if (bluetoothAdapter != null && !bluetoothAdapter.isEnabled()) {
					Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
					// launch bluetooth settings to enabled it
					Services.Log.debug("bluetoothAdapter NOT Enabled, call setting to enabled it");
					if (getActivity()!=null) {
						getActivity().startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT);
						ActivityHelper.registerActionRequestCode(REQUEST_ENABLE_BT);
						return ExternalApiResult.SUCCESS_WAIT;
					}
				}
			}
			return ExternalApiResult.SUCCESS_CONTINUE;
		}

		@NonNull
		@Override
		public ExternalApiResult handleActivityResult(int requestCode, int resultCode, @Nullable Intent result) {
			if (resultCode == Activity.RESULT_OK) {
				Services.Log.debug("Bluetooh settings changed ok!");
			}
			else
			{
				Services.Log.debug("Bluetooh settings not changed.");

			}
			return ExternalApiResult.SUCCESS_CONTINUE;
		}
	};


	// test
	@SuppressWarnings("deprecation")
	private final IMethodInvoker mMethodTest = new IMethodInvoker() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {
			TokenRepository tokenRepository = new TokenRepository(MyApplication.getAppContext());

			/*
			// add fake token and notify
			ListenableFuture<Void> tokenTask = tokenRepository.upsertAsync(
				TokenEntity.create(ExposureNotificationClientWrapper.FAKE_TOKEN_1, false));

			try {
				tokenTask.get();
			}
			catch (ExecutionException | InterruptedException ex) {
				Services.Log.error("Error query tokenTask ");
				Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
				return ExternalApiResult.SUCCESS_CONTINUE;
			}
			*/

			/*
			// Now broadcasts them to the worker.
			Intent intent1 =
				new Intent(MyApplication.getInstance(), ExposureNotificationBroadcastReceiver.class);
			intent1.setAction(ExposureNotificationClient.ACTION_EXPOSURE_STATE_UPDATED);
			intent1.putExtra(
				ExposureNotificationClient.EXTRA_TOKEN,
				ExposureNotificationClientWrapper.FAKE_TOKEN_1);
			MyApplication.getInstance().sendBroadcast(intent1);
			*/

			/*
			//delete tokens
			ListenableFuture<Void> tokenTask = tokenRepository.deleteAllTokensAsync();

			try {
				tokenTask.get();
			}
			catch (ExecutionException | InterruptedException ex) {
				Services.Log.error("Error query tokenTask ");
				Services.Log.debug("Exception detail " + ex.getMessage() + " " + ex.getCause());
				return ExternalApiResult.SUCCESS_CONTINUE;
			}
			*/


			/*
			// Test download files to API
			WorkManager workManager = WorkManager.getInstance(MyApplication.getInstance());
			workManager.enqueue(new OneTimeWorkRequest.Builder(ProvideDiagnosisKeysWorker.class).build());
			*/


			// schedule server api download,
			//ProvideDiagnosisKeysWorker.scheduleDailyProvideDiagnosisKeys(MyApplication.getInstance());
			getKeysDPResult();

			//dataSource.hasDataProvider()
			/* get exposure config
			ExposureConfiguration config = copyExposureConfigurationFromEntity();
			*/

			return ExternalApiResult.SUCCESS_CONTINUE;

		}

	};

	@SuppressWarnings("deprecation")
	private final IMethodInvoker mMethodStartDetectionSession = new IMethodInvoker() {
		@NonNull
		@Override
		public ExternalApiResult invoke(List<Object> parameters) {


			// Test download files to API
			WorkManager workManager = WorkManager.getInstance(MyApplication.getInstance());
			workManager.enqueue(new OneTimeWorkRequest.Builder(ProvideDiagnosisKeysWorker.class).build());

			/*
			//test
			ExternalObjectEvent event = new ExternalObjectEvent(ExposureNotificationsAPI.OBJECT_NAME, ExposureNotificationsAPI.EVENT_EXPOSURE_DETECTED);
			event.fire(new ArrayList<Object>() {
			});
			*/
			return ExternalApiResult.SUCCESS_CONTINUE;
		}

	};
	// test


	public static ArrayList<String> getKeysDPResult() {
		// check app is loaded
		checkMetadata();

		// Test call DP and get uris.
		String dpToCall = getKeysDP();
		//GxObjectDefinition gxObjectDefinition = Services.Application.getGxObject(dpToCall);

		// create object to call
		IApplicationServer server = MyApplication.getApplicationServer(Connectivity.Online);
		IGxObject gxObject = server.getGxObject(dpToCall);

		if (gxObject!=null) {
			// do the call
			PropertiesObject callParameters = new PropertiesObject();
			OutputResult result = gxObject.execute(callParameters);

			if (result.isOk()) {
				// Load urls
				String resultUrls = callParameters.optStringProperty("Returnvalue");
				Services.Log.debug("DP result:  " + resultUrls);

				try {
					JSONArray arrayUrlsJson = new JSONArray(resultUrls);
					if (arrayUrlsJson != null && arrayUrlsJson.length() > 0) {
						JSONObject urlJson = arrayUrlsJson.getJSONObject(0);

						JSONArray arrayKeysUrlsJson = urlJson.getJSONArray("Keys");
						ArrayList<String> urlsArray = new ArrayList<>();
						for (int i = 0; i < arrayKeysUrlsJson.length(); i++) {
							urlsArray.add(arrayKeysUrlsJson.getString(i));
						}
						Services.Log.debug("result" + urlsArray.toString());
						return urlsArray;
					}
				} catch (JSONException ex) {
					Services.Log.error(" error calling DP. Parsing result");
					//should return not result, fail
					return null;
				}
			} else {
				Services.Log.error(" error calling  DP. Call Failed. ");
				//should return not result, fail
				return null;
			}
		}
		else {
			Services.Log.debug(" DP GxObject not found. ");
		}
		return null;
	}



	public static String getKeysDP() {
		String dp = MyApplication.getApp().getMainProperties().optStringProperty(PROPERTY_KEYSDP);
		String callTarget = MetadataLoader.getObjectName(dp);
		return callTarget;
	}

	// permissionHelperCallback, use in start and stop methods.
	private final ExposureNotificationPermissionHelper.Callback permissionHelperCallback = new ExposureNotificationPermissionHelper.Callback() {
		@Override
		public void onFailure() {
			// show error
			Services.Log.debug("method onFailure");

			// set result to false
			if (getAction() != null && getAction().hasOutput())
				getAction().setOutputValue(Expression.Value.newBoolean(false));

			// continue with the actions
			ActionExecution.continueCurrent(getActivity(), false, getAction());

		}

		@Override
		public void onOptOutSuccess() {
			Services.Log.debug("method onOptOutSuccess");

			// return action success
			// Set result to True.
			if (getAction() != null && getAction().hasOutput())
				getAction().setOutputValue(Expression.Value.newBoolean(true));

			// continue action
			ActionExecution.continueCurrent(getActivity(), false, getAction());
		}

		@Override
		@SuppressWarnings("deprecation")
		public void onOptInSuccess() {
			Services.Log.debug("method onOptInSuccess");

			// return action success
			// Set result to True.
			if (getAction() != null && getAction().hasOutput())
				getAction().setOutputValue(Expression.Value.newBoolean(true));

			// raise schedule the daily job of scheduleDailyProvideDiagnosisKeys if time say to do .
			// schedule sync server API , if enabled true
			boolean enabled = ExposureNotificationsAPIOffline.isEnabledInternal();
			Services.Log.debug(" Start success , schedule sync server Enabled: " + enabled);
			if (enabled) {
				boolean shouldRunSync = ExposureNotificationsAPIOffline.shouldRunSync();
				if (shouldRunSync)
				{
					// schedule server api download,
					ExposureNotificationsAPIOffline.sScheduleDaily = true;
					ProvideDiagnosisKeysWorker.scheduleDailyProvideDiagnosisKeys(MyApplication.getInstance());
					//ProvideDiagnosisKeysWorker.scheduleDailyProvideDiagnosisKeysWithDelay(MyApplication.getInstance());
				}
			}

			// continue action
			ActionExecution.continueCurrent(getActivity(), false, getAction());

		}
	};


	// tempExposurePermissionHelperCallback, use in getTempExposure method.
	private final TemporaryExposureKeyHistoryPermissionHelper.Callback tempExposurePermissionHelperCallback = new TemporaryExposureKeyHistoryPermissionHelper.Callback() {
		@Override
		public void onFailure() {
			// show error
			Services.Log.debug("method onFailure");

			// cancel the action
			ActionExecution.cancelCurrent(getAction());
		}

		@Override
		public void onTempKeyOptInSuccess(List<TemporaryExposureKey> temporaryExposureKeys) {
			Services.Log.debug("method onTempKeyOptInSuccess");

			// return action success
			// Set result to True.
			if (temporaryExposureKeys!=null)
			{
				Services.Log.debug("GetTempKeySharing success, return temp keys " + temporaryExposureKeys.toString());

				EntityList temporaryExposureKeysEntityList = convertTempKeyToEntityList(temporaryExposureKeys);
				// return result to caller
				if (getAction() != null && getAction().hasOutput())
					getAction().setOutputValue(Expression.Value.newValueObject(temporaryExposureKeysEntityList));
			}

			// continue action
			ActionExecution.continueCurrent(getActivity(), false, getAction());
		}
	};



}
