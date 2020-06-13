//
//  GXExposureAlertsLocalStorage.swift
//

import Foundation
import ExposureNotification

class GXExposureAlertsLocalStorage {
	
	static let shared = GXExposureAlertsLocalStorage()
	
	struct Notifications {
		static let exposureDetectionMinIntervalDidChange = Notification.Name.init("GXExposureAlertsLocalStorageExposureDetectionMinIntervalDidChange")
	}
	
	init() {
		Keychain.cleanIfReinstalled()
		UserDefaultsConstants.registerDefaults()
	}
	
	private struct UserDefaultsConstants {
		struct Keys {
			static let exposureDetectionMinInterval				= "GXEOEN_minInterval"
			static let exposureInformationUserExplanation		= "GXEOEN_expInfUserExplanation"
			static let reinstallFlag							= "GXEOEN_reinstallFlag"
		}
		
		struct DefaultValues {
			static let exposureDetectionMinInterval				= 1440
		}
		
		static func registerDefaults() {
			UserDefaults.standard.register(defaults: [
				Keys.exposureDetectionMinInterval : DefaultValues.exposureDetectionMinInterval,
			])
		}
	}
	
	private struct Keychain {

		struct Constants {
			static let service = "gx.exposureAlerts"
			
			struct Keys {
				static let lastStartedExposureConfiguration	 		= "lastStartedExposureConfig"
				static let lastExposureDetectionResult				= "lastExposureDetectionResult"
			}
		}
		
		static func cleanIfReinstalled() {
			if UserDefaults.standard.bool(forKey: UserDefaultsConstants.Keys.reinstallFlag) {
				return
			}
			
			if let error = GXKeyChainStore.removeAllItems(forService: Constants.service, accessGroup: GXKeyChainStore.privateAccessGroup()) {
				GXUtilities.tryRecoverFatalError("Could not clean keychain after app re-install: \(error.localizedDescription)")
				return
			}
			
			UserDefaults.standard.set(true, forKey: UserDefaultsConstants.Keys.reinstallFlag)
		}
		
		class Storage<T: Codable> {
			
			let propName: String
			let key: String
			
			init(propName: String, key: String) {
				self.propName = propName
				self.key = key
			}
			
			private var loadedValue: T?? = nil
			
			public var property: T?
			{
				get {
					if let optionalValue = loadedValue {
						return optionalValue
					}
					
					var optionalValue : T? = nil
					gx_dispatch_sync_on_main_queue {
						var data: Data? = nil
						do {
							data = try GXKeyChainStore.data(forKey: self.key,
															service: Constants.service,
															accessGroup: GXKeyChainStore.privateAccessGroup(),
															attributes: nil)
						}
						catch {
							GXUtilities.tryRecoverFatalError("Could not retrieve \(self.propName) from key chain: \(error.localizedDescription)")
						}
						if let data = data {
							do {
								let value = try JSONDecoder().decode(T.self, from: data)
								self.loadedValue = .some(value as T?)
							}
							catch {
								GXUtilities.tryRecoverFatalError("Could not parse \(self.propName) JSON: \(error.localizedDescription)")
							}
						}
						if self.loadedValue == nil {
							self.loadedValue = .some(nil)
						}
						optionalValue = self.loadedValue!
					}
					return optionalValue
				}
				set {
					gx_dispatch_sync_on_main_queue {
						self.loadedValue = newValue
						if let nonOptionalNewValue = newValue {
							let jsonData: Data
							do { jsonData = try JSONEncoder().encode(nonOptionalNewValue) }
							catch {
								GXUtilities.tryRecoverFatalError("Could not encode \(self.propName) JSON: \(error.localizedDescription)")
								return
							}
							if let error = GXKeyChainStore.setData(jsonData,
																   forKey: self.key,
																   service: Constants.service,
																   accessGroup: GXKeyChainStore.privateAccessGroup())
							{
								GXUtilities.tryRecoverFatalError("Could not store \(self.propName) in the keychain: \(error.localizedDescription)")
							}
						}
						else {
							if let error = GXKeyChainStore.removeItem(forKey: self.key,
																	  service: Constants.service,
																	  accessGroup: GXKeyChainStore.privateAccessGroup())
							{
								GXUtilities.tryRecoverFatalError("Could not remove \(self.propName) from keychain: \(error.localizedDescription)")
							}
						}
					}
				}
			}
		}
	}
	
	// MARK - Public Properties
	
	public var exposureDetectionMinInterval: UInt {
		get {
			UInt(UserDefaults.standard.integer(forKey: UserDefaultsConstants.Keys.exposureDetectionMinInterval))
		}
		set {
			let oldValue = exposureDetectionMinInterval
			guard oldValue != newValue else {
				return
			}
			UserDefaults.standard.set(newValue, forKey: UserDefaultsConstants.Keys.exposureDetectionMinInterval)
			NotificationCenter.default.post(name: Notifications.exposureDetectionMinIntervalDidChange, object: self)
		}
	}
		
	public var exposureInformationUserExplanation: String? {
		get {
			UserDefaults.standard.string(forKey: UserDefaultsConstants.Keys.exposureInformationUserExplanation)
		}
		set {
			if let validNewValue = newValue, !validNewValue.isEmpty {
				UserDefaults.standard.set(validNewValue, forKey: UserDefaultsConstants.Keys.exposureInformationUserExplanation)
			}
			else {
				UserDefaults.standard.removeObject(forKey: UserDefaultsConstants.Keys.exposureInformationUserExplanation)
			}
		}
	}
	
	private let lastStartedExposureConfigurationStorage = Keychain.Storage<ExposureConfiguration>(propName: "lastStartedExposureConfiguration", key: Keychain.Constants.Keys.lastStartedExposureConfiguration)
	
	public var lastStartedExposureConfiguration: ExposureConfiguration?
	{
		get { return lastStartedExposureConfigurationStorage.property }
		set { lastStartedExposureConfigurationStorage.property = newValue }
	}
	
	private let lastExposureDetectionResultStorage = Keychain.Storage<ExposureDetectionSessionResult>(propName: "lastExposureDetectionResult", key: Keychain.Constants.Keys.lastExposureDetectionResult)
	
	public var lastExposureDetectionResult: ExposureDetectionSessionResult?
	{
		get { return lastExposureDetectionResultStorage.property }
		set { lastExposureDetectionResultStorage.property = newValue }
	}
}

extension GXExposureAlertsLocalStorage {
	
	struct ExposureConfiguration: Codable {
		var minimumRiskScore: UInt16
		var attenuationLevelValues: [ENRiskLevelValue]
		var attenuationWeight: Double
		var daysSinceLastExposureLevelValues: [ENRiskLevelValue]
		var daysSinceLastExposureWeight: Double
		var durationLevelValues: [ENRiskLevelValue]
		var durationWeight: Double
		var transmissionRiskLevelValues: [ENRiskLevelValue]
		var transmissionRiskWeight: Double
	}
	
	struct ExposureDetectionSessionResult: Codable {
		var id: UUID
		var sessionTimeStamp: Date
		var lastExposureDate: Date
		var matchedKeyCount: UInt
		var maximumRiskScore: UInt16
		var attenuationDurations: [Double]
		var exposuresDetails: [ExposureInfo]
		
		static let empty = ExposureDetectionSessionResult.init(id: UUID(uuidString: "00000000-0000-0000-0000-000000000000")!,
															   sessionTimeStamp: .gxEmptyDateTime(),
															   lastExposureDate: .gxEmpty(),
															   matchedKeyCount: 0,
															   maximumRiskScore: 0,
															   attenuationDurations: [],
															   exposuresDetails: [])
		
		static func newSessionResultWithoutMatches() -> ExposureDetectionSessionResult {
			.init(id: .init(),
				  sessionTimeStamp: Date(),
				  lastExposureDate: .gxEmpty(),
				  matchedKeyCount: 0,
				  maximumRiskScore: 0,
				  attenuationDurations: [],
				  exposuresDetails: [])
		}
	}
	
	struct ExposureInfo: Codable {
		var date: Date
		var duration: TimeInterval
		var transmissionRiskLevel: ENRiskLevel
		var totalRiskScore: UInt16
		var attenuationValue: ENAttenuation
		var attenuationDurations: [Double]
	}
}
