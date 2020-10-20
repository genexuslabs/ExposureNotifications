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

import static com.google.android.apps.exposurenotification.nearby.ProvideDiagnosisKeysWorker.DEFAULT_API_TIMEOUT;

import android.content.Context;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.work.ListenableWorker;
import androidx.work.WorkerParameters;

import com.artech.actions.ExternalObjectEvent;
import com.artech.base.services.Services;
import com.genexus.exposurenotifications.ExposureNotificationsAPI;
import com.google.android.apps.exposurenotification.common.AppExecutors;
import com.google.android.apps.exposurenotification.common.TaskToFutureAdapter;
import com.google.android.apps.exposurenotification.storage.TokenEntity;
import com.google.android.apps.exposurenotification.storage.TokenRepository;
import com.google.android.gms.nearby.exposurenotification.ExposureConfiguration;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

/**
 * Performs work for {@value com.google.android.gms.nearby.exposurenotification.ExposureNotificationClient#ACTION_EXPOSURE_STATE_UPDATED}
 * broadcast from exposure notification API.
 */
public class StateUpdatedWorker extends ListenableWorker {

  private static final String TAG = "StateUpdatedWorker";

  private static final String EXPOSURE_NOTIFICATION_CHANNEL_ID =
      "ApolloExposureNotificationCallback.EXPOSURE_NOTIFICATION_CHANNEL_ID";
  public static final String ACTION_LAUNCH_FROM_EXPOSURE_NOTIFICATION =
      "com.google.android.apps.exposurenotification.ACTION_LAUNCH_FROM_EXPOSURE_NOTIFICATION";

  private final Context context;
  private final TokenRepository tokenRepository;

  public StateUpdatedWorker(
      @NonNull Context context, @NonNull WorkerParameters workerParams) {
    super(context, workerParams);
    this.context = context;
    this.tokenRepository = new TokenRepository(context);
  }

  @NonNull
  @Override
  public ListenableFuture<Result> startWork() {
    final String token = getInputData().getString(ExposureNotificationClient.EXTRA_TOKEN);
    if (token == null) {
      return Futures.immediateFuture(Result.failure());
    } else {
      return FluentFuture.from(TaskToFutureAdapter.getFutureWithTimeout(
          ExposureNotificationClientWrapper.get(context).getExposureSummary(token),
          DEFAULT_API_TIMEOUT.toMillis(),
          TimeUnit.MILLISECONDS,
          AppExecutors.getScheduledExecutor()))
          .transformAsync((exposureSummary) -> {
            if (exposureSummary.getMatchedKeyCount() > 0) {
              	// Positive so show a notification and update the token.
				Services.Log.debug("StateUpdatedWorker may call EVENT_EXPOSURE_DETECTED event, matches plus 0 ");

				// only call event for exposure plus minimum risk
				ExposureConfiguration exposureConfiguration = ExposureNotificationsAPI.copyExposureConfigurationFromEntity();

				Services.Log.debug("RiskScore configuration: " + exposureConfiguration.getMinimumRiskScore() + " exposure: " + exposureSummary.getMaximumRiskScore() );
				Services.Log.debug("Exposure data : " + exposureSummary.toString() );

				if (exposureSummary.getMaximumRiskScore()>= exposureConfiguration.getMinimumRiskScore()) {
					// add token to last available token
					TokenRepository.upsertToken(TokenEntity.create(token, true));


					if (shouldRunEvent(token)) {
						setTokenTime(token);
						Services.Log.debug("start EVENT_EXPOSURE_DETECTED event");

						Services.Log.debug(TAG, "isMainThread? " + isMainThread());
						if (!isMainThread())
						{
							Services.Log.debug(TAG, "sleep for 2 sec ");
							Thread.sleep(2000);
						}
						// raise EO event when ready.
						Services.Log.debug("call EVENT_EXPOSURE_DETECTED event");
						ExternalObjectEvent event = new ExternalObjectEvent(ExposureNotificationsAPI.OBJECT_NAME, ExposureNotificationsAPI.EVENT_EXPOSURE_DETECTED);
						event.fire(new ArrayList<Object>() {
						});

					}
				}
				// Update the TokenEntity by upserting with the same token.
				return tokenRepository.upsertAsync(TokenEntity.create(token, true));
	        } else {
              	// No matches so we show no notification and just delete the token.
				Services.Log.debug("StateUpdatedWorker no matches 0 ");

				return tokenRepository.deleteByTokensAsync(token);
            }
          }, AppExecutors.getBackgroundExecutor())
          .transform((v) -> Result.success(), AppExecutors.getLightweightExecutor())
          .catching(Exception.class, x -> Result.failure(), AppExecutors.getLightweightExecutor());
    }
  }

	private boolean isMainThread() {
		return Looper.getMainLooper().getThread() == Thread.currentThread();
	}

  	// check call event twice for same token.
	private static HashMap<String, Long> tokenTimes = new HashMap<>();

	private static boolean shouldRunEvent(String token) {
		long minTimeBetweenEvent = 1; // in minutes
		long nowTime = new Date().getTime();
		long lastSync = getTokenTime(token);
		boolean shouldRunSync = true;

		if (lastSync != 0 && ((nowTime - lastSync) < (minTimeBetweenEvent * 1000 * 60))) {
			shouldRunSync = false;
			Services.Log.debug("MinTimeBetween Events time not happened yet, do nothing.");
		}
		return shouldRunSync;
	}

	private static long getTokenTime(String token) {
		if (tokenTimes!=null)
		{
			if (tokenTimes.containsKey(token))
				return tokenTimes.get(token);
		}
		return 0;
	}

	private static void setTokenTime(String token) {
		if (tokenTimes!=null)
		{
			long nowTime = new Date().getTime();
			tokenTimes.put(token, nowTime);
		}
	}
	// check call event twice for same token.



}
