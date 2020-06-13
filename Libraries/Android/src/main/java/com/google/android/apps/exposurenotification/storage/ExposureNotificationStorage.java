package com.google.android.apps.exposurenotification.storage;

import androidx.annotation.NonNull;

import com.artech.base.model.Entity;
import com.artech.base.model.EntityFactory;
import com.artech.base.services.ClientStorage;
import com.artech.base.services.Services;
import com.artech.base.utils.Strings;

import static com.artech.base.model.Entity.JSONFORMAT_INTERNAL;

public class ExposureNotificationStorage {


	private static final String FIELD_EXPOSURE_CONFIGURATION = "exposure_configuration";
	private static final String FIELD_EXPOSURE_DETECTION_MIN_INTERVAL = "exposure_detection_min_interval";
	private static final String FIELD_EXPOSURE_USER_EXPLANATION = "exposure_user_explanation";
	private static final String FIELD_LAST_EXPOSURE_DETECTION_TIMESTAMP = "exposure_detection_last_performed";
	private static final String FIELD_START_CALLED = "exposure_start_called";

	private static ClientStorage sStorage;


	@NonNull
	private static synchronized ClientStorage getStorage()
	{
		final String STORAGE_KEY = "exposure_notification_pref";

		if (sStorage == null)
			sStorage = Services.Application.getClientStorage(STORAGE_KEY);

		return sStorage;
	}

	// Exposure Configuration
	public static void setExposureConfiguration(Entity exposureConfig)
	{
		String valueString = exposureConfig.toString();
		getStorage().putString(FIELD_EXPOSURE_CONFIGURATION, valueString);

	}

	public static Entity getExposureConfiguration()
	{
		Entity exposureConfiguration = EntityFactory.newSdt("ExposureAlerts.ExposureConfiguration");
		// TODO: GX add a default value for ExposureConfiguration?
		String valueString = getStorage().getString(FIELD_EXPOSURE_CONFIGURATION, "");
		if (Strings.hasValue(valueString))
		{
			exposureConfiguration.deserialize(Services.Serializer.createNode(valueString), JSONFORMAT_INTERNAL);
		}
		return exposureConfiguration;
	}

	public static void setExposureDetectionMinInterval(int exposureDetectionMinInterval)
	{
		getStorage().putString(FIELD_EXPOSURE_DETECTION_MIN_INTERVAL, Integer.toString(exposureDetectionMinInterval));
	}

	public static int getExposureDetectionMinInterval()
	{
		String valueString = getStorage().getString(FIELD_EXPOSURE_DETECTION_MIN_INTERVAL, "1440");
		try {
			Integer result = Integer.valueOf(valueString);
			return result;
		}
		catch (NumberFormatException ex)
		{
			Services.Log.error("Error reading " + FIELD_EXPOSURE_DETECTION_MIN_INTERVAL);
			return 1440;
		}
	}

	public static void setExposureInformationUserExplanation(String userExplanation)
	{
		getStorage().putString(FIELD_EXPOSURE_USER_EXPLANATION, userExplanation);
	}

	public static String getExposureInformationUserExplanation()
	{
		String valueString = getStorage().getString(FIELD_EXPOSURE_USER_EXPLANATION, "");
		return valueString;
	}

	public static void setLastPerformedExposureDetectionTimeStamp(long timeStamp)
	{
		getStorage().putString(FIELD_LAST_EXPOSURE_DETECTION_TIMESTAMP, Long.toString(timeStamp));
	}

	public static long getLastPerformedExposureDetectionTimeStamp()
	{
		String valueString = getStorage().getString(FIELD_LAST_EXPOSURE_DETECTION_TIMESTAMP, "0");
		if (Services.Strings.hasValue(valueString)) {
			try {
				long timeStamp = Long.parseLong(valueString);
				return timeStamp;
			}
			catch (NumberFormatException ex)
			{
				Services.Log.error("Error reading " + FIELD_LAST_EXPOSURE_DETECTION_TIMESTAMP);
			}
		}
		return 0;

	}

	public static void setStartCalled(boolean startCalled)
	{
		getStorage().putBoolean(FIELD_START_CALLED, startCalled);
	}

	public static boolean getStartCalled()
	{
		return getStorage().getBoolean(FIELD_START_CALLED, false);
	}

}
