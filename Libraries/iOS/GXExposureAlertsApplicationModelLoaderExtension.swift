//
//  GXExposureAlertsApplicationModelLoaderExtension.swift
//

import Foundation
import GXObjectsModel

class GXExposureAlertsApplicationModelLoaderExtension: NSObject, GXApplicationModelLoaderExtension {
	
	static let appEntryPointPropertyDiagnosisKeysProviderKey = "appENDiagnosisKeysDP"
	
	// MARK - GXApplicationModelLoaderExtension
	
	@objc(applicationEntryPointPropertiesFromMetadataLoaderBlock:error:)
	func applicationEntryPointProperties(fromMetadataLoaderBlock metadataLoaderBlock: (String) -> Any?) throws -> [String : Any] {
		if let enDiagnosisKeysProviderMetadata = GXUtilities.nonEmptyString(from: metadataLoaderBlock("ExposureAlerts_ExposureNotificationDiagnosisKeysProvider")) {
			guard let enDiagnosisKeysProvider = GXObjectHelper.parseObjectName(ofTypeEnum: .dataProvider, from: enDiagnosisKeysProviderMetadata) else {
				throw NSError.init(domain: GXModelErrorDomain, code: 0, developerDescription: "")
			}
			return [type(of: self).appEntryPointPropertyDiagnosisKeysProviderKey: enDiagnosisKeysProvider]
		}
		return [:]
	}
}
