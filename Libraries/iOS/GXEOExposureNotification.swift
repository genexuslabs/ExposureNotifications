//
//  GXEOExposureNotification.swift
//

import Foundation
import ExposureNotification

@objc(GXEOExposureNotification)
class GXEOExposureNotification: GXExternalObjectBase {
	
	static let externalObjectName = "ExposureAlerts.ExposureNotification"
	
	static let notAvailableError = NSError.defaultGXError(withDeveloperDescription: "Exposure Notification API is not available.")
	
	override var externalObjectName: String {
		return type(of: self).externalObjectName
	}
		
	// MARK - Public
	
	public class var isAvailable: Bool {
		if #available(iOS 13.5, *) {
			return runtimeIsAvailable
		}
		else {
			return false
		}
	}
	
	@available(iOS 13.5, *)
	private class var runtimeIsAvailable: Bool {
		return ENManager.authorizationStatus != .restricted && GXExposureAlertsManager.isDeviceSupported
	}
	
	public class func isEnabled(completion: @escaping (Bool) -> Void) {
		guard #available(iOS 13.5, *), runtimeIsAvailable else {
			completion(false)
			return
		}
		
		GXExposureAlertsManager.shared.isEnabled { (result) in
			switch result {
			case .success(let enabled):
				completion(enabled)
			case .failure(let error):
				let logService = GXLog.loggerService()
				if logService.isLogEnabled {
					logService.logMessage(error.localizedDescription, for: .general, with: .error, logToConsole: true)
				}
				completion(false)
			}
		}
	}
	
	public class var authorizationStatus: GXAuthorizationStatusType {
		guard #available(iOS 13.5, *), runtimeIsAvailable else {
			return .restricted
		}
		switch ENManager.authorizationStatus {
		case .unknown:
			return .notDetermined
		case .restricted:
			return .restricted
		case .notAuthorized:
			return .denied
		case .authorized:
			return .authorized
		@unknown default:
			fatalError()
		}
	}
	
	public class func isBluetoothEnabled(completion: @escaping (Bool) -> Void) {
		guard #available(iOS 13.5, *), runtimeIsAvailable else {
			completion(false)
			return
		}
		
		GXExposureAlertsManager.shared.isBluetoothEnabled { (result) in
			switch result {
			case .success(let enabled):
				completion(enabled)
			case .failure(let error):
				let logService = GXLog.loggerService()
				if logService.isLogEnabled {
					logService.logMessage(error.localizedDescription, for: .general, with: .error, logToConsole: true)
				}
				completion(false)
			}
		}
	}
	
	public class var lastExposureDetectionResult: exposurealerts_SdtExposureDetectionSessionResult {
		let sessionResult = GXExposureAlertsLocalStorage.shared.lastExposureDetectionResult ?? .empty
		let result = exposurealerts_SdtExposureDetectionSessionResult()
		result.gxTv_SdtExposureDetectionSessionResult_Id = sessionResult.id.uuidString
		result.gxTv_SdtExposureDetectionSessionResult_Sessiontimestamp = sessionResult.sessionTimeStamp
		result.gxTv_SdtExposureDetectionSessionResult_Lastexposuredate = sessionResult.lastExposureDate
		result.gxTv_SdtExposureDetectionSessionResult_Matchedkeycount = Int(sessionResult.matchedKeyCount)
		result.gxTv_SdtExposureDetectionSessionResult_Maximumriskscore = Int(sessionResult.maximumRiskScore)
		result.gxTv_SdtExposureDetectionSessionResult_Attenuationdurations = GXObjectCollection(array: sessionResult.attenuationDurations)
		return result
	}
	
	public class var exposureDetected: Bool {
		guard let sessionResult = GXExposureAlertsLocalStorage.shared.lastExposureDetectionResult else {
			return false
		}
		return sessionResult.matchedKeyCount > 0
	}

	public class var exposureDetectionMinInterval: UInt {
		get {
			return GXExposureAlertsLocalStorage.shared.exposureDetectionMinInterval
		}
		set {
			GXExposureAlertsLocalStorage.shared.exposureDetectionMinInterval = newValue
		}
	}
		
	public class var exposureInformationUserExplanation: String? {
		get {
			return GXExposureAlertsLocalStorage.shared.exposureInformationUserExplanation
		}
		set {
			GXExposureAlertsLocalStorage.shared.exposureInformationUserExplanation = newValue
		}
	}
		
	public class func start(exposureConfiguration: GXSDTDataProtocol, completion: @escaping (Error?) -> Void) {
		guard #available(iOS 13.5, *), runtimeIsAvailable else {
			completion(notAvailableError)
			return
		}
		
		func field<T>(_ fieldSpecifier: String) -> T? {
			sdtDataTypedField(sdtData: exposureConfiguration,
							  fieldSpecifier: fieldSpecifier,
							  sdtDataName: "ExposureAlerts.ExposureConfiguration")
		}
		func riskScoreField(_ fieldSpecifier: String) -> ENRiskScore {
			return (field(fieldSpecifier) as NSNumber?)?.uint8Value ?? 0
		}
		func levelValuesField(_ fieldSpecifier: String) -> [NSNumber] {
			return (field(fieldSpecifier) as GXSDTDataCollectionProtocol?)?.sdtDataCollectionItems as! [NSNumber]? ?? Array(repeating: NSNumber(value: 0), count: 8)
		}
		func weightField(_ fieldSpecifier: String) -> Double {
			return (field(fieldSpecifier) as NSNumber?)?.doubleValue ?? 0
		}
		
		let config = ENExposureConfiguration()
		config.minimumRiskScore = riskScoreField("MinimumRiskScore")
		config.attenuationLevelValues = levelValuesField("AttenuationScores")
		config.attenuationWeight = weightField("AttenuationWeight")
		config.daysSinceLastExposureLevelValues = levelValuesField("DaysSinceLastExposureScores")
		config.daysSinceLastExposureWeight = weightField("DaysSinceLastExposureWeight")
		config.durationLevelValues = levelValuesField("DurationScores")
		config.durationWeight = weightField("DurationWeight")
		config.transmissionRiskLevelValues = levelValuesField("TransmissionRiskScores")
		config.transmissionRiskWeight = weightField("TransmissionRiskWeight")
		GXExposureAlertsManager.shared.start(config: config, completion: completion)
	}
	
	public class func stop(completion: @escaping (Error?) -> Void) {
		guard #available(iOS 13.5, *), runtimeIsAvailable else {
			completion(notAvailableError)
			return
		}
		
		GXExposureAlertsManager.shared.stop(completion: completion)
	}
	
	public class func temporaryExposureKeyHistoryForSharing(completion: @escaping (Result<GXObjectCollection, Error>) -> Void) {
		guard #available(iOS 13.5, *), runtimeIsAvailable else {
			completion(.failure(notAvailableError))
			return
		}
		
		GXExposureAlertsManager.shared.getDiagnosisKeysWithCompletionHandler { result in
			switch result {
			case .failure(let error):
				if (error as? ENError)?.code == ENError.notAuthorized, ENManager.authorizationStatus == .authorized {
					completion(.failure(NSError.userCancelledError()))
				}
				else {
					completion(.failure(error))
				}
			case .success(let temporaryExposureKeys):
				let typeName = NSStringFromClass(exposurealerts_SdtTemporaryExposureKey.self)
				let items: [exposurealerts_SdtTemporaryExposureKey] = temporaryExposureKeys.map { key in
					let item = exposurealerts_SdtTemporaryExposureKey()
					item.gxTv_SdtTemporaryExposureKey_Keydata = key.keyData.base64EncodedString()
					item.gxTv_SdtTemporaryExposureKey_Rollingperiod = Int64(key.rollingPeriod)
					item.gxTv_SdtTemporaryExposureKey_Rollingstartintervalnumber = Int64(key.rollingStartNumber)
					item.gxTv_SdtTemporaryExposureKey_Transmissionrisklevel = Int(key.transmissionRiskLevel)
					return item
				}
				let result = GXObjectCollection(typeName: typeName, items: items)
				completion(.success(result))
			}
		}
	}
	
	public class func lastExposureDetectionSessionDetails(completion: @escaping (Result<GXObjectCollection, Error>) -> Void) {
		guard #available(iOS 13.5, *), runtimeIsAvailable else {
			completion(.failure(notAvailableError))
			return
		}
		
		let sessionResult = GXExposureAlertsLocalStorage.shared.lastExposureDetectionResult
		let typeName = NSStringFromClass(exposurealerts_SdtExposureInfo.self)
		let items: [exposurealerts_SdtExposureInfo]
		items = sessionResult?.exposuresDetails.map { exposureInfo in
			let item = exposurealerts_SdtExposureInfo()
			item.gxTv_SdtExposureInfo_Date = exposureInfo.date
			item.gxTv_SdtExposureInfo_Duration = Int(exposureInfo.duration / 60) // TimeInterval to minutes
			item.gxTv_SdtExposureInfo_Transmissionrisklevel = Int(exposureInfo.transmissionRiskLevel)
			item.gxTv_SdtExposureInfo_Totalriskscore = Int(exposureInfo.totalRiskScore)
			item.gxTv_SdtExposureInfo_Attenuationvalue = Int(exposureInfo.attenuationValue)
			item.gxTv_SdtExposureInfo_Attenuationdurations = GXObjectCollection(array: exposureInfo.attenuationDurations)
			return item
		} ?? []
		let result = GXObjectCollection(typeName: typeName, items: items)
		completion(.success(result))
	}
	
	public class func resetLastExposureDetectionResult(completion: @escaping (Error?) -> Void) {
		guard #available(iOS 13.5, *), runtimeIsAvailable else {
			completion(notAvailableError)
			return
		}
		
		GXExposureAlertsLocalStorage.shared.lastExposureDetectionResult = nil
		completion(nil)
	}
	
	// MARK - Private Helpers
	
	private class func syncExecuteLogIfError(asyncBlockError: (@escaping (Error?) -> Void) -> Void, successHandler: () -> Void, errorHandler: ((Error) -> Void)) {
		assert(!Thread.isMainThread)
		var error: Error? = nil
		let dGroup = DispatchGroup()
		dGroup.enter()
		asyncBlockError { asyncError in
			error = asyncError
			dGroup.leave()
		}
		dGroup.wait()
		if error == nil {
			successHandler()
		}
		else {
			let logService = GXLog.loggerService()
			if logService.isLogEnabled {
				logService.logMessage(error!.localizedDescription, for: .general, with: .error, logToConsole: true)
			}
			errorHandler(error!)
		}
	}
	
	private class func syncExecuteLogIfError<T>(asyncBlock: (@escaping (Result<T, Error>) -> Void) -> Void, successHandler: (T) -> Void, errorHandler: ((Error) -> Void)) {
		assert(!Thread.isMainThread)
		var result: Result<T, Error>! = nil
		let dGroup = DispatchGroup()
		dGroup.enter()
		asyncBlock { asyncResult in
			result = asyncResult
			dGroup.leave()
		}
		dGroup.wait()
		switch result! {
		case .success(let successResult):
			successHandler(successResult)
		case .failure(let error):
			let logService = GXLog.loggerService()
			if logService.isLogEnabled {
				logService.logMessage(error.localizedDescription, for: .general, with: .error, logToConsole: true)
			}
			errorHandler(error)
		}
	}
	
	private class func syncExecuteGetterLogIfError(asyncBlockError: (@escaping (Error?) -> Void) -> Void) -> Bool {
		var result: Bool? = nil
		syncExecuteLogIfError(asyncBlockError: asyncBlockError, successHandler: { result = true }) { _ in result = false }
		return result!
	}
	
	private class func syncExecuteGetterLogIfError<T>(asyncBlock: (@escaping (Result<T, Error>) -> Void) -> Void, defaultOnError errorDefaultResolver: @autoclosure () -> T) -> T {
		var result: T! = nil
		syncExecuteLogIfError(asyncBlock: asyncBlock, successHandler: { result = $0 }) { _ in result = errorDefaultResolver() }
		return result!
	}
	
	private class func syncExecuteGetter<T>(asyncBlock: (@escaping (T) -> Void) -> Void) -> T {
		assert(!Thread.isMainThread)
		var result: T! = nil
		let dGroup = DispatchGroup()
		dGroup.enter()
		asyncBlock {
			result = $0
			dGroup.leave()
		}
		dGroup.wait()
		return result!
	}
	
	private class func sdtDataTypedField<T>(sdtData: GXSDTDataProtocol, fieldSpecifier: String, sdtDataName: @autoclosure () -> String) -> T? {
		if let fieldValue = sdtData.valueForFieldSpecifier(fieldSpecifier) {
			if let typedFieldValue = fieldValue as? T {
				return typedFieldValue
			}
			else {
				GXUtilities.tryHandleFatalError("\(sdtDataName()) field \(fieldSpecifier) invalid type. Expected \(T.self) but was \(type(of: fieldValue)).")
			}
		}
		else {
			GXUtilities.tryHandleFatalError("\(sdtDataName()) field \(fieldSpecifier) was nil.")
		}
		return nil
	}
	
	private class func sdtDataExposureConfigurationField<T>(_ exposureConfiguration: GXSDTDataProtocol, fieldSpecifier: String) -> T? {
		return sdtDataTypedField(sdtData: exposureConfiguration,
								 fieldSpecifier: fieldSpecifier,
								 sdtDataName: "ExposureAlerts.ExposureConfiguration")
	}
}

extension GXEOExposureNotification {
	
	// MARK - GXEOProtocol_GXEOExposureNotification
	
	@objc(isAvailable)
	var isAvailable: Bool {
		return type(of: self).isAvailable
	}
	
	@objc(isEnabled)
	var isEnabled: Bool {
		return type(of: self).syncExecuteGetter(asyncBlock: type(of: self).isEnabled(completion:))
	}
	
	@objc(authorizationStatus)
	var authorizationStatus: Int {
		return Int(type(of: self).authorizationStatus.rawValue)
	}
	
	@objc(bluetoothEnabled)
	var bluetoothEnabled: Bool {
		return type(of: self).syncExecuteGetter(asyncBlock: type(of: self).isBluetoothEnabled(completion:))
	}
	
	@objc(lastExposureDetectionResult)
	var lastExposureDetectionResult: exposurealerts_SdtExposureDetectionSessionResult {
		return type(of: self).lastExposureDetectionResult
	}
	
	@objc(exposureDetected)
	var exposureDetected: Bool {
		return type(of: self).exposureDetected
	}
	
	@objc(exposureDetectionMinInterval)
	var exposureDetectionMinInterval: Int {
		get {
			return Int(type(of: self).exposureDetectionMinInterval)
		}
		set {
			if newValue >= 0 {
				type(of: self).exposureDetectionMinInterval = UInt(newValue)
			}
		}
	}
	
	@objc(exposureInformationUserExplanation)
	var exposureInformationUserExplanation: String {
		get {
			return type(of: self).exposureInformationUserExplanation ?? ""
		}
		set {
			type(of: self).exposureInformationUserExplanation = newValue
		}
	}
		
	@objc(start:)
	static func start(_ exposureConfiguration: exposurealerts_SdtExposureConfiguration) -> Bool {
		return syncExecuteGetterLogIfError { (completion) in
			start(exposureConfiguration: exposureConfiguration, completion: completion)
		}
	}
	
	@objc(stop)
	static func stop() -> Bool {
		return syncExecuteGetterLogIfError(asyncBlockError: stop(completion:))
	}
	
	@objc(temporaryExposureKeyHistoryForSharing)
	static func temporaryExposureKeyHistoryForSharing() -> GXObjectCollection {
		return syncExecuteGetterLogIfError(asyncBlock: temporaryExposureKeyHistoryForSharing(completion:), defaultOnError: GXObjectCollection.init(typeName: NSStringFromClass(exposurealerts_SdtTemporaryExposureKey.self)))
	}
	
	@objc(lastExposureDetectionSessionDetails)
	static func lastExposureDetectionSessionDetails() -> GXObjectCollection {
		return syncExecuteGetterLogIfError(asyncBlock: lastExposureDetectionSessionDetails(completion:),
										   defaultOnError: GXObjectCollection.init(typeName: NSStringFromClass(exposurealerts_SdtExposureInfo.self)))
	}
	
	@objc(resetLastExposureDetectionResult)
	static func resetLastExposureDetectionResult() -> Bool {
		return syncExecuteGetterLogIfError(asyncBlockError: resetLastExposureDetectionResult(completion:))
	}
}
