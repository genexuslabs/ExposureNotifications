package com.genexus.exposurenotifications;

import android.content.Context;
import android.content.Intent;

import com.google.android.apps.exposurenotification.common.AppExecutors;
import com.google.android.apps.exposurenotification.common.TaskToFutureAdapter;
import com.google.android.apps.exposurenotification.nearby.ExposureNotificationClientWrapper;
import com.google.android.gms.nearby.exposurenotification.ExposureInformation;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient;
import com.google.android.gms.nearby.exposurenotification.ExposureSummary;
import com.google.common.util.concurrent.ListenableFuture;

import org.threeten.bp.Duration;

import java.util.List;
import java.util.concurrent.TimeUnit;

public class ExposureNotificationsAPIHelper {

	private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

	public static ListenableFuture<List<ExposureInformation>> getExposureInformation(Context context, String token) {
		return TaskToFutureAdapter.getFutureWithTimeout(
			ExposureNotificationClientWrapper.get(context).getExposureInformation(token),
			API_TIMEOUT.toMillis(),
			TimeUnit.MILLISECONDS,
			AppExecutors.getScheduledExecutor());
	}

	public static ListenableFuture<ExposureSummary> getExposureSummary(Context context, String token) {
		return TaskToFutureAdapter.getFutureWithTimeout(
			ExposureNotificationClientWrapper.get(context).getExposureSummary(token),
			API_TIMEOUT.toMillis(),
			TimeUnit.MILLISECONDS,
			AppExecutors.getScheduledExecutor());
	}

	public static boolean hasExposureNotificationModule(Context context) {
		Intent intent = new Intent(ExposureNotificationClient.ACTION_EXPOSURE_NOTIFICATION_SETTINGS);
		return intent.resolveActivity(context.getPackageManager()) != null;

	}
}
