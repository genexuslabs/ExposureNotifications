/*
 * Copyright 2020 Google LLC
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.google.android.apps.exposurenotification.nearby;

import android.content.Context;
import androidx.annotation.NonNull;
import androidx.work.Constraints;
import androidx.work.ExistingPeriodicWorkPolicy;
import androidx.work.ListenableWorker;
import androidx.work.NetworkType;
import androidx.work.OneTimeWorkRequest;
import androidx.work.PeriodicWorkRequest;
import androidx.work.WorkManager;
import androidx.work.WorkerParameters;

import com.artech.application.MyApplication;
import com.artech.base.services.Services;
import com.genexus.exposurenotifications.ExposureNotificationsAPIOffline;
import com.google.android.apps.exposurenotification.common.AppExecutors;
import com.google.android.apps.exposurenotification.common.TaskToFutureAdapter;
import com.google.android.apps.exposurenotification.network.DiagnosisKeys;
import com.google.android.apps.exposurenotification.storage.TokenEntity;
import com.google.android.apps.exposurenotification.storage.TokenRepository;
import com.google.common.io.BaseEncoding;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import org.threeten.bp.Duration;

/**
 * Performs work to provide diagnosis keys to the exposure notifications API.
 */
public class ProvideDiagnosisKeysWorker extends ListenableWorker {

  private static final String TAG = "ProvideDiagnosisKeysWkr";

  public static final Duration DEFAULT_API_TIMEOUT = Duration.ofSeconds(15);

  public static final String WORKER_NAME = "ProvideDiagnosisKeysWorker";
  private static final BaseEncoding BASE64_LOWER = BaseEncoding.base64();
  private static final int RANDOM_TOKEN_BYTE_LENGTH = 32;

  private final DiagnosisKeys diagnosisKeys;
  private final DiagnosisKeyFileSubmitter submitter;
  private final SecureRandom secureRandom;
  private final TokenRepository tokenRepository;

	public static boolean sShouldRetry = true;

	public ProvideDiagnosisKeysWorker(@NonNull Context context,
      @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
    diagnosisKeys = new DiagnosisKeys(context);
    submitter = new DiagnosisKeyFileSubmitter(context);
    secureRandom = new SecureRandom();
    tokenRepository = new TokenRepository(context);
  }

  private String generateRandomToken() {
    byte[] bytes = new byte[RANDOM_TOKEN_BYTE_LENGTH];
    secureRandom.nextBytes(bytes);
    return BASE64_LOWER.encode(bytes);
  }

  @NonNull
  @Override
  @SuppressWarnings("deprecation")
  public ListenableFuture<Result> startWork() {
	  Services.Log.debug(TAG, "Starting worker downloading diagnosis key files and submitting "
        + "them to the API for exposure detection, then storing the token used.");
    final String token = generateRandomToken();
	  Services.Log.debug("startWork " + token);
    return FluentFuture.from(TaskToFutureAdapter
        .getFutureWithTimeout(
            ExposureNotificationClientWrapper.get(getApplicationContext()).isEnabled(),
            DEFAULT_API_TIMEOUT.toMillis(),
            TimeUnit.MILLISECONDS,
            AppExecutors.getScheduledExecutor()))
        .transformAsync((isEnabled) -> {
          // Only continue if it is enabled.
          if (isEnabled) {
            return diagnosisKeys.download();
          } else {
            // Stop here because things aren't enabled. Will still return successful though.
            return Futures.immediateFailedFuture(new NotEnabledException());
          }
        }, AppExecutors.getBackgroundExecutor())
        .transformAsync((batches) -> submitter.submitFiles(batches, token),
            AppExecutors.getBackgroundExecutor())
        .transformAsync(
            done -> tokenRepository.upsertAsync(TokenEntity.create(token, false)),
            AppExecutors.getBackgroundExecutor())
        .transform(done -> Result.success(), AppExecutors.getLightweightExecutor())
        .catching(NotEnabledException.class, x -> {
          // Not enabled. Return as success.
          return Result.success();
        }, AppExecutors.getBackgroundExecutor())
        .catching(Exception.class, x -> {
			Services.Log.error(TAG, "Failure to provide diagnosis keys", x);

			// TODO: consider a retry in X min again for N times?
			Services.Log.debug(" failure to provide diagnosis keys , retry : " );
			if (sShouldRetry) {
				Services.Log.debug(" retry one more time in 10 minutes: ");
				// Test download files to API
				WorkManager workManager = WorkManager.getInstance(MyApplication.getInstance());
				workManager.enqueue(new OneTimeWorkRequest.Builder(ProvideDiagnosisKeysWorker.class)
					.setInitialDelay(10, TimeUnit.MINUTES)
					.setConstraints(
						new Constraints.Builder()
							.setRequiresBatteryNotLow(true)
							.setRequiredNetworkType(NetworkType.CONNECTED).build())
					.build());
				sShouldRetry = false;
			}
			return Result.failure();

        }, AppExecutors.getBackgroundExecutor());
    // TODO: consider a retry strategy
  }

  /**
   * Schedules a job that runs once a day to fetch diagnosis keys from a server and to provide them
   * to the exposure notifications API.
   *
   * <p>This job will only be run when idle, not low battery and with network connection.
   *
   * <p>  schedule the daily job
   */
  public static void scheduleDailyProvideDiagnosisKeys(Context context) {
    WorkManager workManager = WorkManager.getInstance(context);
    //  use our interval in minutes.
	  int repeatInterval = ExposureNotificationsAPIOffline.getExposureDetectionMinInterval();
		Services.Log.debug(" Schedule ProvideDiagnosisKeys worker by " + repeatInterval + " minutes ");
    PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
        ProvideDiagnosisKeysWorker.class, repeatInterval, TimeUnit.MINUTES)
        .setConstraints(
            new Constraints.Builder()
                .setRequiresBatteryNotLow(true)
                //.setRequiresDeviceIdle(true)
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build())
        .build();
    workManager
        .enqueueUniquePeriodicWork(WORKER_NAME, ExistingPeriodicWorkPolicy.REPLACE, workRequest);
  }

	public static void scheduleDailyProvideDiagnosisKeysWithDelay(Context context) {
		WorkManager workManager = WorkManager.getInstance(context);
		//  use our interval in minutes.
		int repeatInterval = ExposureNotificationsAPIOffline.getExposureDetectionMinInterval();
		Services.Log.debug(" Schedule ProvideDiagnosisKeys worker by " + repeatInterval + " minutes with delay");
		PeriodicWorkRequest workRequest = new PeriodicWorkRequest.Builder(
			ProvideDiagnosisKeysWorker.class, repeatInterval, TimeUnit.MINUTES)
			.setConstraints(
				new Constraints.Builder()
					.setRequiresBatteryNotLow(true)
					//.setRequiresDeviceIdle(true)
					.setRequiredNetworkType(NetworkType.CONNECTED)
					.build())
			.setInitialDelay(1, TimeUnit.MINUTES)
			.build();
		workManager
			.enqueueUniquePeriodicWork(WORKER_NAME, ExistingPeriodicWorkPolicy.REPLACE, workRequest);
	}

  private static class NotEnabledException extends Exception {

  }

}
