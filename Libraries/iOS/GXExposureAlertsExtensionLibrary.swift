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
		if #available(iOS 12.5, *) {
			if GXExposureAlertsManager.isENManagerAvailable, GXExposureAlertsManager.isDeviceSupported {
				// Initialize manager as soon as posible to register background task on app launch
				_ = GXExposureAlertsManager.shared
			}
		}
	}
}
