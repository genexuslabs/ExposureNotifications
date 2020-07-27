package com.google.android.apps.exposurenotification.storage;

import android.content.Context;

import androidx.annotation.NonNull;

import com.artech.base.services.ClientStorage;
import com.artech.base.services.Services;
import com.artech.base.utils.Strings;
import com.google.android.apps.exposurenotification.common.AppExecutors;
import com.google.android.apps.exposurenotification.common.TaskToFutureAdapter;
import com.google.android.gms.tasks.Task;
import com.google.android.gms.tasks.Tasks;
import com.google.common.util.concurrent.ListenableFuture;

import org.threeten.bp.Duration;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class TokenRepository {

	private static final Duration API_REPO_TIMEOUT = Duration.ofSeconds(10);

	private static final String FIELD_LAST_TOKEN_RESPONDED = "token_responded";

	// TODO: store a jsonArray of token? For now store only last one.
	public TokenRepository(Context context) {
	}

	private static ClientStorage sStorage;

	@NonNull
	private static synchronized ClientStorage getStorage()
	{
		final String STORAGE_KEY = "exposure_notification_tokens";

		if (sStorage == null)
			sStorage = Services.Application.getClientStorage(STORAGE_KEY);

		return sStorage;
	}


	public ListenableFuture<List<TokenEntity>> getAllAsync() {
		Task<List<TokenEntity>> deleteAllTokens = Tasks.call(new GetAllTokensCallable());
		return TaskToFutureAdapter.getFutureWithTimeout(
			deleteAllTokens,
		  	API_REPO_TIMEOUT.toMillis(),
		  	TimeUnit.MILLISECONDS,
		  	AppExecutors.getScheduledExecutor());
  	}

  	public ListenableFuture<Void> upsertAsync(TokenEntity entity) {
		Task<Void> upsertTokens = Tasks.call(new UpsertTokenCallable(entity));
		return TaskToFutureAdapter.getFutureWithTimeout(
			upsertTokens,
		  	API_REPO_TIMEOUT.toMillis(),
		  	TimeUnit.MILLISECONDS,
		  	AppExecutors.getScheduledExecutor());
  	}

  	public ListenableFuture<Void> deleteByTokensAsync(String... tokens) {
		// TODO: implement a to store severals tokens?. delete all token for now
		Task<Void> deleteAllTokens = Tasks.call(new DeleteAllTokensCallable());
		return TaskToFutureAdapter.getFutureWithTimeout(
			deleteAllTokens,
		  	API_REPO_TIMEOUT.toMillis(),
		  	TimeUnit.MILLISECONDS,
		  	AppExecutors.getScheduledExecutor());
  	}

	public ListenableFuture<Void> deleteAllTokensAsync() {
		Task<Void> deleteAllTokens = Tasks.call(new DeleteAllTokensCallable());
		return TaskToFutureAdapter.getFutureWithTimeout(
			deleteAllTokens,
			API_REPO_TIMEOUT.toMillis(),
			TimeUnit.MILLISECONDS,
			AppExecutors.getScheduledExecutor());
	}


	public static class GetAllTokensCallable implements Callable<List<TokenEntity>> {
		@Override
		public List<TokenEntity> call() throws Exception {
			List<TokenEntity> list = new ArrayList<>();
			String tokenValueJson = getStorage().getString(FIELD_LAST_TOKEN_RESPONDED, "");
			if (Strings.hasValue(tokenValueJson)) {
				TokenEntity tokenEntity = new TokenEntity("", false);
				tokenEntity.fromJson(tokenValueJson);
				list.add(tokenEntity);
			}
			return list;
		}
	}

	public static class UpsertTokenCallable implements Callable<Void> {
		private TokenEntity mEntity = null;
		public UpsertTokenCallable(TokenEntity entity)
		{
			mEntity = entity;
		}

		@Override
		public Void call() throws Exception {
			if (mEntity!=null && mEntity.isResponded()) {
				String tokenValueJson = mEntity.toJson();
				getStorage().putStringSecure(FIELD_LAST_TOKEN_RESPONDED, tokenValueJson);
			}
			return null;
		}
	}

	public static class DeleteAllTokensCallable implements Callable<Void> {
		@Override
		public Void call() throws Exception {
			getStorage().remove(FIELD_LAST_TOKEN_RESPONDED);
			return null;
		}
	}

	public static void upsertToken(TokenEntity entity)
	{
		if (entity!=null && entity.isResponded()) {
			String tokenValueJson = entity.toJson();
			getStorage().putStringSecure(FIELD_LAST_TOKEN_RESPONDED, tokenValueJson);
		}
	}

	public static class DummyCallable implements Callable<Void> {
		@Override
		public Void call() throws Exception {
			return null;
		}
	}
}
