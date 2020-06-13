package com.genexus.exposurenotifications;

import android.content.Context;

import com.artech.externalapi.ExternalApiDefinition;
import com.artech.externalapi.ExternalApiFactory;
import com.artech.framework.GenexusModule;
import com.jakewharton.threetenabp.AndroidThreeTen;

public class ExposureNotificationsModule implements GenexusModule {

	@Override
	public void initialize(Context context)
	{
		ExternalApiFactory.addApi(new ExternalApiDefinition(ExposureNotificationsAPI.OBJECT_NAME, ExposureNotificationsAPI.class));

		AndroidThreeTen.init(context);
	}
}
