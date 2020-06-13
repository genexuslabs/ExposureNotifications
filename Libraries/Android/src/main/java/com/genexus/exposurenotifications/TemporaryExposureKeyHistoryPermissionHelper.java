
package com.genexus.exposurenotifications;

import android.app.Activity;
import android.content.Context;
import android.content.IntentSender.SendIntentException;
import android.util.Log;
import android.view.View;
import android.view.Window;

import androidx.lifecycle.LifecycleObserver;

import com.google.android.apps.exposurenotification.activities.utils.RequestCodes;
import com.google.android.apps.exposurenotification.common.AppExecutors;
import com.google.android.apps.exposurenotification.common.TaskToFutureAdapter;
import com.google.android.apps.exposurenotification.nearby.ExposureNotificationClientWrapper;
import com.google.android.gms.common.api.ApiException;
import com.google.android.gms.nearby.exposurenotification.ExposureNotificationStatusCodes;
import com.google.android.gms.nearby.exposurenotification.TemporaryExposureKey;
import com.google.android.material.snackbar.Snackbar;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import org.checkerframework.checker.nullness.compatqual.NullableDecl;
import org.threeten.bp.Duration;

import java.util.List;
import java.util.concurrent.TimeUnit;


/**
 * A helper to let user opt-in to share temp keys, and start sharing after opting in.
 * <p/>
 * Usage:
 * <ul>
 *   <li>Fragment implements {@link .Callback}</li>
 *   <li>Fragment calls {@link #optInAndGetTemporaryExposureKeyHistory}</li>
 *   <li>Fragment overrides {@code onActivityResult} and call {@link #onResolutionComplete}</li>
 *   <li>Fragment's hosting activity overrides {@code onActivityResult} and delegates the
 *   result handling to fragment</li>
 * </ul>
 */
public class TemporaryExposureKeyHistoryPermissionHelper implements LifecycleObserver {

  public interface Callback {

    default void onFailure() {
    }

    void onTempKeyOptInSuccess(List<TemporaryExposureKey> temporaryExposureKeys);


  }

	private static final String TAG = "ENTempPermissionHelper";
	private static final Duration API_TIMEOUT = Duration.ofSeconds(10);

  private final Activity host;
  private final Callback callback;
  private boolean hasInFlightResolution;

  public TemporaryExposureKeyHistoryPermissionHelper(Activity host, Callback callback) {
    this.host = host;
    this.callback = callback;
  }

  /**
   * Starts the exposure getTemporaryExposureKeyHistory. Possibly show permission dialog if user hasn't do it before.
   *
   * @param activity The root view to show any error UI.
   */
  public List<TemporaryExposureKey> optInAndGetTemporaryExposureKeyHistory(Activity activity) {
    if (activity == null) {
      Log.w(TAG, "No activity, skipping");
      return null;
    }
    FluentFuture.from(getTemporaryExposureKeyHistory(activity))
        .transformAsync(
			getTemporaryExposureKeyHistory -> {
              if (getTemporaryExposureKeyHistory != null ) {
                Log.d(TAG, "Already has result. Return it.");
                return Futures.immediateFuture(getTemporaryExposureKeyHistory);
              }
              Log.d(TAG, "Not enabled. Starting resolution.");
              return getTemporaryExposureKeyHistory(activity);
            }, AppExecutors.getLightweightExecutor())
        .addCallback(new FutureCallback<List<TemporaryExposureKey>>() {

        	@Override
          public void onSuccess(@NullableDecl List<TemporaryExposureKey> result) {
            hasInFlightResolution = false;
            callback.onTempKeyOptInSuccess(result);
          }

          @Override
          public void onFailure(Throwable exception) {
            if (!(exception instanceof ApiException) || hasInFlightResolution) {
              Log.e(TAG, "Unknown error or has hasInFlightResolution", exception);
              // Reset hasInFlightResolution so we don't block future resolution if user wants to
              // try again manually
              showError();
              return;
            }
            ApiException apiException = (ApiException) exception;
            if (apiException.getStatusCode() ==
                ExposureNotificationStatusCodes.RESOLUTION_REQUIRED) {
              try {
                hasInFlightResolution = true;
                apiException.getStatus()
                    .startResolutionForResult(activity,
                        RequestCodes.REQUEST_CODE_GET_TEMP_EXPOSURE_KEY_HISTORY);
              } catch (SendIntentException e) {
                Log.w(TAG, "Error calling startResolutionForResult, sending to settings",
                    apiException);
                showError();
              }
            } else {
              Log.w(TAG, "No RESOLUTION_REQUIRED in result, sending to settings", apiException);
              showError();
            }
          }
        }, MoreExecutors.directExecutor());
    return null;
  }

	public static ListenableFuture<List<TemporaryExposureKey>> getTemporaryExposureKeyHistory(Context context) {
		return TaskToFutureAdapter.getFutureWithTimeout(
			ExposureNotificationClientWrapper.get(context).getTemporaryExposureKeyHistory(),
			API_TIMEOUT.toMillis(),
			TimeUnit.MILLISECONDS,
			AppExecutors.getScheduledExecutor());
	}

  /**
   * Called when opt-in resolution is completed by user.
   * <p/>
   * Modeled after {@code Activity#onActivityResult} as that's how the API sends callback to apps.
   *
   * @param activity The root view to show any error UI.
   */
  public void onResolutionComplete(int requestCode, int resultCode, Activity activity) {
    if (requestCode != RequestCodes.REQUEST_CODE_GET_TEMP_EXPOSURE_KEY_HISTORY) {
      return;
    }
    if (resultCode == Activity.RESULT_OK) {
		optInAndGetTemporaryExposureKeyHistory(activity);
    } else {
      hasInFlightResolution = false;
      callback.onFailure();
    }
  }



  private void showError() {
    hasInFlightResolution = false;
    if (host != null && host.getWindow()!=null) {
		View content = host.getWindow().findViewById(Window.ID_ANDROID_CONTENT);
		if (content != null) {
			Snackbar.make(content, R.string.generic_error_message, Snackbar.LENGTH_LONG).show();
		}
    }
    callback.onFailure();
  }

}
