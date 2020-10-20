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
import android.util.Log;

import com.artech.base.services.Services;
import com.google.android.apps.exposurenotification.common.AppExecutors;
import com.google.android.apps.exposurenotification.common.TaskToFutureAdapter;
import com.google.android.apps.exposurenotification.network.KeyFileBatch;
import com.google.android.apps.exposurenotification.storage.ExposureNotificationStorage;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import java.io.File;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.threeten.bp.Duration;

/**
 * A thin class to take responsibility for submitting downloaded Diagnosis Key files to the Google
 * Play Services Exposure Notifications API.
 */
public class DiagnosisKeyFileSubmitter {
  private static final String TAG = "KeyFileSubmitter";
	// Use a very very long timeout, in case of a stress-test that supplies a very large number of
	// diagnosis key files.
	private static final Duration PROVIDE_KEYS_TIMEOUT = Duration.ofMinutes(1);

  private final ExposureNotificationClientWrapper client;

  public DiagnosisKeyFileSubmitter(Context context) {
    client = ExposureNotificationClientWrapper.get(context);
  }

  /**
   * Accepts batches of key files, and submits them to provideDiagnosisKeys(), and returns a future
   * representing the completion of that task.
   *
   * <p>This naive implementation is not robust to individual failures. In fact, a single failure
   * will fail the entire operation. A more robust implementation would support retries, partial
   * completion, and other robustness measures.
   *
   * <p>Returns early if given an empty list of batches.
   */
  public ListenableFuture<?> submitFiles(List<KeyFileBatch> batches, String token) {
    if (batches.isEmpty()) {
      Log.d(TAG, "No files to provide to google play services.");
      return Futures.immediateFuture(null);
    }
    Log.d(TAG, "Providing  " + batches.size() + " diagnosis key batches to google play services.");
    List<ListenableFuture<?>> batchCompletions = new ArrayList<>();
    for (KeyFileBatch b : batches) {
      batchCompletions.add(submitBatch(b, token));
    }

    ListenableFuture<?> allDone = Futures.allAsList(batchCompletions);
    allDone.addListener(
        () -> {
          for (KeyFileBatch b : batches) {
            for (File f : b.files()) {
				Services.Log.debug(" delete temp file " + f.getAbsolutePath() );
              f.delete();
            }
          }
        	// set time last sync send to API correctly.
			Services.Log.debug(" set time last sync success " );
			long nowTime = new Date().getTime();
			ExposureNotificationStorage.setLastPerformedExposureDetectionTimeStamp(nowTime);
		},
        AppExecutors.getBackgroundExecutor());

    return allDone;
  }

  private ListenableFuture<?> submitBatch(KeyFileBatch batch, String token) {
	  Services.Log.debug(" submitBatch : token " + token);
  	return TaskToFutureAdapter.getFutureWithTimeout(
        client.provideDiagnosisKeys(batch.files(), token),
		PROVIDE_KEYS_TIMEOUT.toMillis(),
        TimeUnit.MILLISECONDS,
        AppExecutors.getScheduledExecutor());
  }
}
