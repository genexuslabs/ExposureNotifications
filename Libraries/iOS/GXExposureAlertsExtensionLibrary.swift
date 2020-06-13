//
//  GXExposureAlertsExtensionLibrary.swift
//

import GXCoreBL

@objc(GXExposureAlertsExtensionLibrary)
public class GXExposureAlertsExtensionLibrary: NSObject, GXExtensionLibraryProtocol {
	
	public func initializeExtensionLibrary(withContext context: GXExtensionLibraryContext) {
		GXApplication.register(GXExposureAlertsApplicationModelLoaderExtension())
		GXActionExternalObjectHandler.register(GXActionExtObjExposureNotificationHandler.self, forExternalObjectName:
			GXEOExposureNotification.externalObjectName)
		if #available(iOS 13.5, *) {
			// Initialize manager as soon as posible to register background task on app launch
		    _ = GXExposureAlertsManager.shared
		}
	}
}
