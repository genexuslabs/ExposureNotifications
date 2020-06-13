//
//  GXExposureAlertsManager.swift
//

import Foundation
import ExposureNotification
import BackgroundTasks

@available(iOS 13.5, *)
class GXExposureAlertsManager {
	
	static let shared = GXExposureAlertsManager()
	
	internal static let isDeviceSupported: Bool = UIDevice.current.model == "iPhone"
	
	private let enManager = ENManager()
	private var activationError: Error? = nil {
		didSet {
			if let error = activationError {
				let logService = GXLog.loggerService()
				if logService.isLogEnabled {
					logService.logMessage("Exposure Notification manager activation failed with error: \(error)", for: .general, with: .error, logToConsole: true)
				}
			}
		}
	}
	private var waitingForActivationOp: BlockOperation? = nil
	
	init() {
		type(of: self).registerDetectionBackgroundTask()
		guard type(of: self).isDeviceSupported else {
			activationError = NSError.defaultGXError(withDeveloperDescription: "Exposure Notification API is not available on this device.")
			return
		}
		
		let waitingForActivationOp_ = BlockOperation()
		waitingForActivationOp = waitingForActivationOp_
		enManager.activate { error in
			self.waitingForActivationOp = nil
			self.activationError = error
			gx_dispatch_on_main_queue {
				if !waitingForActivationOp_.executionBlocks.isEmpty {
					waitingForActivationOp_.start()
					DispatchQueue.global(qos: .background).async {
						waitingForActivationOp_.waitUntilFinished() // retain op until finised
					}
				}
				type(of: self).scheduleBackgroundTaskIfNeeded()
			}
		}
		let nc = NotificationCenter.default
		nc.addObserver(self, selector: #selector(self.onExposureDetectionMinIntervalDidChange),
					   name: GXExposureAlertsLocalStorage.Notifications.exposureDetectionMinIntervalDidChange,
					   object: GXExposureAlertsLocalStorage.shared)
	}
	
    deinit {
		guard type(of: self).isDeviceSupported else {
			return
		}
        enManager.invalidate()
		NotificationCenter.default.removeObserver(self)
    }
	
	
	public func isEnabled(_ completion: @escaping (Result<Bool, Error>) -> Void) {
		executeAfterSuccessfulActivation(failureCompletion: completion) {
			var enabled = self.enManager.exposureNotificationEnabled
			if enabled && GXExposureAlertsLocalStorage.shared.lastStartedExposureConfiguration == nil {
				// Re-enable is required.
				enabled = false
				self.stop { (_) in }
				let logService = GXLog.loggerService()
				if logService.isLogEnabled {
					logService.logMessage("Exposure Notification manager was enabled but has no configuration. Re-enable is required, disable.", for: .general, with: .debug, logToConsole: true)
				}
			}
			completion(.success(enabled))
		}
	}
	
	public func isBluetoothEnabled(_ completion: @escaping (Result<Bool, Error>) -> Void) {
		executeAfterSuccessfulActivation(failureCompletion: completion) {
			completion(.success(self.enManager.exposureNotificationStatus != .bluetoothOff))
		}
	}
	
	public func start(config: ENExposureConfiguration, completion: @escaping ENErrorHandler) {
		self.setEnabled(true) { (error) in
			if error == nil {
				GXExposureAlertsLocalStorage.shared.lastStartedExposureConfiguration = .init(enConfig: config)
				type(of: self).scheduleBackgroundTaskIfNeeded()
			}
			completion(error)
		}
	}
	
	public func stop(completion: @escaping ENErrorHandler) {
		self.setEnabled(false) { (error) in
			if error == nil {
				GXExposureAlertsLocalStorage.shared.lastStartedExposureConfiguration = nil
				type(of: self).cancelPendingBackgroundTask()
			}
			completion(error)
		}
	}
	
	public func getDiagnosisKeysWithCompletionHandler(completion: @escaping (Result<[ENTemporaryExposureKey], Error>) -> Void) {
		executeAfterSuccessfulActivation(failureCompletion: completion) {
			self.enManager.getDiagnosisKeys { (keys, error) in
				if let error = error {
					completion(.failure(error))
				}
				else {
					completion(.success(keys!))
				}
			}
		}
	}
	
	// MARK - Notification Observers
	
	@objc private func onExposureDetectionMinIntervalDidChange() {
		type(of: self).scheduleBackgroundTaskIfNeeded()
	}
	
	// MARK - Private Helpers
	
	private func executeAfterActivation(_ handler: @escaping () -> Void) {
		if waitingForActivationOp != nil {
			gx_dispatch_on_main_queue {
				if let op = self.waitingForActivationOp {
					op.addExecutionBlock(handler)
				}
				else {
					handler()
				}
			}
		}
		else {
			handler()
		}
	}
	
	private func executeAfterSuccessfulActivation<T>(failureCompletion: @escaping (Result<T, Error>) -> Void, successfulHandler: @escaping () -> Void) {
		executeAfterActivation {
			if let error = self.activationError {
				failureCompletion(.failure(error))
			}
			else {
				successfulHandler()
			}
		}
	}
	
	private func executeAfterSuccessfulActivation(errorCompletion: @escaping (Error?) -> Void, successfulHandler: @escaping () -> Void) {
		executeAfterActivation {
			if let error = self.activationError {
				errorCompletion(error)
			}
			else {
				successfulHandler()
			}
		}
	}
	
	private func setEnabled(_ enabled: Bool, completion: @escaping ENErrorHandler) {
		executeAfterSuccessfulActivation(errorCompletion: completion) {
			let willRequestAuthorization = enabled && ENManager.authorizationStatus == .unknown
			self.enManager.setExposureNotificationEnabled(enabled) { (error) in
				guard let enError = error as? ENError else {
					completion(error)
					return
				}
				
				let completionError: Error
				let userCanceledPermissionRequest = willRequestAuthorization && enError.code == ENError.notAuthorized
				if userCanceledPermissionRequest {
					completionError = NSError.userCancelledError()
				}
				else if ENManager.authorizationStatus == .notAuthorized {
					completionError = NSError.permissionDeniedErrorWithGoToSettingsRecoveryAttempter()
				}
				else {
					completionError = enError
				}
				completion(completionError)
			}
		}
	}
	   
	private var detectingExposures = false
	
	private var keysDownloaderURLSession: URLSession? = nil {
		didSet {
			if let oldSession = oldValue, oldValue != keysDownloaderURLSession {
				oldSession.invalidateAndCancel()
			}
		}
	}
    
	@discardableResult
	internal func detectExposures(completionHandler: ((Bool) -> Void)? = nil) -> Progress {
		let progress = Progress()
		executeAfterActivation {
			guard self.activationError == nil else {
				completionHandler?(false)
				return
			}
			self.activatedDetectExposures(progress: progress, completionHandler: completionHandler)
		}
		return progress
	}
	
	private func activatedDetectExposures(progress: Progress, completionHandler: ((Bool) -> Void)? = nil) {
		// Disallow concurrent exposure detection, because if allowed we might try to detect the same diagnosis keys more than once
		guard !detectingExposures, !progress.isCancelled, ENManager.authorizationStatus == .authorized,
			let configuration = GXExposureAlertsLocalStorage.shared.lastStartedExposureConfiguration else {
			completionHandler?(false)
			return
		}
		detectingExposures = true
		
		let logService = GXLog.loggerService()
		if logService.isLogEnabled {
			logService.logMessage("Exposure detection session START", for: .general, with: .debug, logToConsole: true)
		}
		
		let tmpLocalKeysDirectoryURL = FileManager.default.temporaryDirectory.appendingPathComponent("GXExposureNotificationSession", isDirectory: true)
		
		func finish(_ result: Result<GXExposureAlertsLocalStorage.ExposureDetectionSessionResult, Error>) {
			
			try? FileManager.default.removeItem(at: tmpLocalKeysDirectoryURL) // Clean-up
			
			let success: Bool
			if progress.isCancelled {
				GXExposureAlertsManager.scheduleBackgroundTaskIfNeeded(earliestBeginDate: nil, verifyPendingTasks: false)
				success = false
			} else {
				switch result {
				case let .success(sessionResult):
					GXExposureAlertsLocalStorage.shared.lastExposureDetectionResult = sessionResult
					GXExposureAlertsManager.scheduleBackgroundTaskIfNeeded(earliestBeginDate: nil, verifyPendingTasks: false)
					if logService.isLogEnabled {
						logService.logMessage("Exposure detection session ended: \(sessionResult.sessionTimeStamp) with matches: \(sessionResult.matchedKeyCount)", for: .general, with: .debug, logToConsole: true)
					}
					success = true
				case let .failure(error):
					var retryTimeInterval: TimeInterval? = nil
					let nsError = error as NSError
					if nsError.isNetworkPossibleError() {
						retryTimeInterval = 10 * 60
						let logService = GXLog.loggerService()
						if logService.isLogEnabled {
							logService.logMessage("Exposure detection session failed with network error: \(error)", for: .network, with: .debug, logToConsole: true)
						}
					}
					else {
						let logService = GXLog.loggerService()
						if logService.isLogEnabled, !nsError.isUserCancelledError() {
							logService.logMessage("Exposure detection session failed with error: \(error)", for: .general, with: .error, logToConsole: true)
						}
						if let enError = error as? ENError {
							switch enError.code {
							case .restricted:
								break
							case .insufficientStorage, .insufficientMemory:
								retryTimeInterval = 6 * 60 * 60
							default:
								retryTimeInterval = 12 * 60 * 60
							}
						}
						else {
							retryTimeInterval = 12 * 60 * 60
						}
					}
					if let retryTimeInterval = retryTimeInterval {
						GXExposureAlertsManager.scheduleBackgroundTaskIfNeeded(earliestBeginDate: Date(timeIntervalSinceNow: retryTimeInterval), verifyPendingTasks: false)
					}
					success = false
				}
			}
			
			detectingExposures = false
			if logService.isLogEnabled {
				logService.logMessage("Exposure detection session END (Success: \(success))", for: .general, with: .debug, logToConsole: true)
			}
			if success, let sessionResult = GXExposureAlertsLocalStorage.shared.lastExposureDetectionResult, sessionResult.matchedKeyCount > 0 {
				gx_dispatch_on_main_queue { // Fire Event on main queue
					let eventName = GXEOExposureNotification.externalObjectName.appending(".OnExposureDetected")
					if let completionHandler = completionHandler, GXExecutionEnvironmentHelper.applicationState == .background {
						let completionDispatchGroup = DispatchGroup()
						completionDispatchGroup.enter()
						GXActionExObjEventsHelper.dispatchExternalObjectEvent(eventName,
																			  withParameters: nil,
																			  outParametersIndexes: nil,
																			  serialHandling: false,
																			  concurrencyMode: .default)
						{ (_) in
							completionDispatchGroup.leave()
						}
						DispatchQueue.global(qos: .utility).async {
							_ = completionDispatchGroup.wait(timeout: .now() + .seconds(3))
							completionHandler(true)
						}
					}
					else {
						GXActionExObjEventsHelper.dispatchExternalObjectEvent(eventName)
						completionHandler?(true)
					}
				}
			}
			else {
				completionHandler?(success)
			}
		}
		
		downloadDiagnosisKeyFileURLs { result in
			switch result {
			case let .failure(error):
				finish(.failure(error))
				return
			case let .success(remoteURLs):
				guard !remoteURLs.isEmpty else {
					finish(.success(.newSessionResultWithoutMatches()))
					return
				}
				
				try? FileManager.default.removeItem(at: tmpLocalKeysDirectoryURL) // Clean-up previous session
				do { try FileManager.default.createDirectory(at: tmpLocalKeysDirectoryURL, withIntermediateDirectories: true, attributes: nil) }
				catch {
					finish(.failure(error))
					return
				}
				
				if logService.isLogEnabled {
					logService.logMessage("Exposure detection session keys download START (Count: \(remoteURLs.count))", for: .general, with: .debug, logToConsole: true)
				}
				let dispatchGroup = DispatchGroup()
				var localURLResults = [Result<URL, Error>]()
				let urlSession = self.keysDownloaderURLSession ?? URLSession(configuration: .default)
				self.keysDownloaderURLSession = urlSession
				for remoteURL in remoteURLs {
					dispatchGroup.enter()
					let downloadTask = urlSession.downloadTask(with: remoteURL) { [weak urlSession] (downloadedURL, _, downloadError) in
						defer {
							dispatchGroup.leave()
						}
						guard !progress.isCancelled else { return }
						let result: Result<URL, Error>
						if let downloadError = downloadError {
							result = .failure(downloadError)
						}
						else {
							let tmpLocalKeyURL = tmpLocalKeysDirectoryURL.appendingPathComponent(remoteURL.lastPathComponent, isDirectory: false)
							do {
								try FileManager.default.moveItem(at: downloadedURL!, to: tmpLocalKeyURL)
								result = .success(tmpLocalKeyURL)
							}
							catch {
								result = .failure(error)
							}
						}
						localURLResults.append(result)
						if case .failure(_) = result {
							if self.keysDownloaderURLSession == urlSession {
								self.keysDownloaderURLSession = nil // calls invalidateAndCancel()
							}
						}
					}
					downloadTask.resume()
				}
				dispatchGroup.notify(queue: .global(qos: .utility)) {
					if logService.isLogEnabled {
						logService.logMessage("Exposure detection session keys download END", for: .general, with: .debug, logToConsole: true)
					}
					guard !progress.isCancelled else {
						finish(.failure(NSError.userCancelledError()))
						return
					}
					var diagnosisKeyURLs: [(bin: URL, sig: URL)] = [] // .bin and .sig file name must match and be unique
					do {
						let localZipsURLs = try localURLResults.map({ (result) -> URL in
							switch result {
							case let .success(localURL):
								return localURL
							case let .failure(error):
								throw error
							}
						})
						for localZipURL in localZipsURLs {
							let keyName = localZipURL.deletingPathExtension().lastPathComponent
							let unzippedURL = localZipURL.deletingLastPathComponent().appendingPathComponent("\(keyName)_unzipped", isDirectory: true)
							let zipArchive = ZipArchive()
							var unzipSuccess = zipArchive.unzipOpenFile(localZipURL.path)
							if unzipSuccess {
								unzipSuccess = zipArchive.unzipFile(to: unzippedURL.path, overWrite: true)
								unzipSuccess = zipArchive.unzipCloseFile() && unzipSuccess
							}
							guard unzipSuccess else {
								throw NSError.defaultGXError(withDeveloperDescription: "Could not unzip key file: \(localZipURL.path)")
							}
							let exportBinURL = unzippedURL.appendingPathComponent("export.bin", isDirectory: false)
							let exportSigURL = unzippedURL.appendingPathComponent("export.sig", isDirectory: false)
							let renamedBinURL = unzippedURL.appendingPathComponent("\(keyName).bin", isDirectory: false)
							let renamedSigURL = unzippedURL.appendingPathComponent("\(keyName).sig", isDirectory: false)
							try FileManager.default.moveItem(at: exportBinURL, to: renamedBinURL)
							try FileManager.default.moveItem(at: exportSigURL, to: renamedSigURL)
							diagnosisKeyURLs.append((bin: renamedBinURL, sig: renamedSigURL))
						}
					}
					catch {
						finish(.failure(error))
						return
					}
					
					if logService.isLogEnabled {
						logService.logMessage("Exposure detection session ENManager.detectExposures START", for: .general, with: .debug, logToConsole: true)
					}
					let enConfig = configuration.createENExposureConfiguration()
					let minimumRiskScore = enConfig.minimumRiskScore
					self.enManager.detectExposures(configuration: enConfig,
												   diagnosisKeyURLs: diagnosisKeyURLs.flatMap { [$0.bin, $0.sig] })
					{ optionalSummary, optionalError in
						
						if logService.isLogEnabled {
							logService.logMessage("Exposure detection session ENManager.detectExposures END", for: .general, with: .debug, logToConsole: true)
						}
						if let error = optionalError {
							finish(.failure(error))
							return
						}
						guard !progress.isCancelled else {
							finish(.failure(NSError.userCancelledError()))
							return
						}
						
						let summary = optionalSummary!
						let hasMatches = summary.matchedKeyCount > 0
						guard hasMatches else {
							finish(.success(.newSessionResultWithoutMatches()))
							return
						}
						
						func finishWithSuccess(withExposureDetails exposureDetails: [ENExposureInfo]?) {
							let secondsSinceLastExposure = TimeInterval(summary.daysSinceLastExposure * 24 * 60 * 60)
							let lastExposureDate = Date.gxDateByRemovingTimePart(from: Date(timeIntervalSinceNow: -secondsSinceLastExposure))
							let exposureDetailsResult: [GXExposureAlertsLocalStorage.ExposureInfo]
							exposureDetailsResult = exposureDetails?.map { exposureInfo in
								let totalRiskScore: UInt16
								if summary.maximumRiskScore == ENRiskScore.max,
									let totalRiskScoreNum = GXUtilities.unsignedIntegerNumber(fromValue: summary.metadata?["totalRiskScoreFullRange"]) {
									totalRiskScore = totalRiskScoreNum.uint16Value
								}
								else {
									totalRiskScore = UInt16(exposureInfo.totalRiskScore)
								}
								return .init(date: Date.gxDateByRemovingDatePart(from: exposureInfo.date),
											 duration: exposureInfo.duration,
											 transmissionRiskLevel: exposureInfo.transmissionRiskLevel,
											 totalRiskScore: totalRiskScore,
											 attenuationValue: exposureInfo.attenuationValue,
											 attenuationDurations: exposureInfo.attenuationDurations.map { $0.doubleValue })
								} ?? []
							let sessionResult: GXExposureAlertsLocalStorage.ExposureDetectionSessionResult
							
							let maximumRiskScore: UInt16
							if summary.maximumRiskScore == ENRiskScore.max,
								let maximumRiskScoreNum = GXUtilities.unsignedIntegerNumber(fromValue: summary.metadata?["maximumRiskScoreFullRange"]) {
								maximumRiskScore = maximumRiskScoreNum.uint16Value
							}
							else {
								maximumRiskScore = UInt16(summary.maximumRiskScore)
							}
							sessionResult = .init(id: UUID(),
												  sessionTimeStamp: Date(),
												  lastExposureDate: lastExposureDate,
												  matchedKeyCount: UInt(summary.matchedKeyCount),
												  maximumRiskScore: maximumRiskScore,
												  attenuationDurations: summary.attenuationDurations.map { $0.doubleValue },
												  exposuresDetails: exposureDetailsResult)
							finish(.success(sessionResult))
						}
						guard summary.maximumRiskScore >= minimumRiskScore,
							let userExplanation = GXExposureAlertsLocalStorage.shared.exposureInformationUserExplanation else {
							finishWithSuccess(withExposureDetails: nil)
							return
						}
						
						if logService.isLogEnabled {
							logService.logMessage("Exposure detection session ENManager.getExposureInfo START", for: .general, with: .debug, logToConsole: true)
						}
						self.enManager.getExposureInfo(summary: summary, userExplanation: userExplanation) { exposures, error in
							if logService.isLogEnabled {
								logService.logMessage("Exposure detection session ENManager.getExposureInfo END", for: .general, with: .debug, logToConsole: true)
							}
							if let error = error {
								finish(.failure(error))
							}
							else {
								guard !progress.isCancelled else {
									finish(.failure(NSError.userCancelledError()))
									return
								}
								finishWithSuccess(withExposureDetails: exposures!)
							}
						}
					}
				}
			}
		}
	}
	
	@discardableResult
	private func downloadDiagnosisKeyFileURLs(allowWaitingForAppModel: Bool = true, completion: @escaping (Result<[URL], Error>) -> Void) -> GXCancelableOperation? {
		guard let appModel = GXApplicationModel.current() else {
			if allowWaitingForAppModel {
				let logService = GXLog.loggerService()
				if logService.isLogEnabled {
					logService.logMessage("Waiting for GXModel to load while downloading DiagnosisKeyDataProviderResult.", for: .general, with: .debug, logToConsole: true)
				}
				var observerObj: NSObjectProtocol? = nil
				func removeObserverIfNeeded() {
					if let observerObj_ = observerObj {
						observerObj = nil
						NotificationCenter.default.removeObserver(observerObj_)
					}
				}
				var innerCancelableOp: GXCancelableOperation? = nil
				let cancelableOp = GXCancelableOperationWithBlock.init {
					innerCancelableOp?.cancel()
					completion(.failure(NSError.userCancelledError()))
				}
				observerObj = NotificationCenter.default.addObserver(forName: .GXModelDidChangeCurrentModel, object: GXModel.self, queue: .main, using: { [weak self] _ in
					if !cancelableOp.isCancelled {
						innerCancelableOp = self?.downloadDiagnosisKeyFileURLs(allowWaitingForAppModel: false, completion: completion)
					}
					removeObserverIfNeeded()
				})
				DispatchQueue.main.asyncAfter(deadline: .now() + .seconds(10)) { // Timeout after 10 seconds
					cancelableOp.cancel()
					removeObserverIfNeeded()
				}
				return cancelableOp
			}
			else {
				completion(.failure(NSError.defaultGXError(withDeveloperDescription: "GXModel not loaded while downloading DiagnosisKeyDataProviderResult.")))
				return nil
			}
		}
		
		let diagnosisKeysDPPropKey = GXExposureAlertsApplicationModelLoaderExtension.appEntryPointPropertyDiagnosisKeysProviderKey
		guard let diagnosisKeysDPName = appModel.appEntryPoint?.value(forAppEntryPointProperty: diagnosisKeysDPPropKey) as? String else {
			let error = GXUtilities.tryHandleFatalError("GXModel Exposure Notification Diagnosis Keys Provider property not found while downloading DiagnosisKeyDataProviderResult.")
			completion(.failure(error))
			return nil
		}
		
		let executionOptions = GXProcedureExecutionOptions()
		executionOptions.shouldRetryOnSecurityCheckFailure = false
		executionOptions.shouldProcessMessagesOutput = false
		let helperOptions = GXProcedureHelperExecutionOptions()
		helperOptions.executionOptions = executionOptions
		return GXProcedureHelper.execute(diagnosisKeysDPName, params: [NSNull()], options: helperOptions) { (result) in
			DispatchQueue.global(qos: .utility).async {
				switch result {
				case .failure(let error):
					completion(.failure(error))
				case .success(let outParams):
					var remoteURLs: [URL] = []
					var parseURLSuccess = false
					let dpResult = GXSDTData.fromStructureDataTypeName("ExposureAlerts.DiagnosisKeyDataProviderResult",
																	   value: outParams.first,
																	   fieldIsCollection: false)
					if let keysCollection = dpResult.valueForFieldSpecifier("Keys") as? GXSDTDataCollectionProtocol {
						parseURLSuccess = true
						for keysCollectionItem in keysCollection.sdtDataCollectionItems {
							guard let remoteURLString = keysCollectionItem as? String,
								let remoteURL = URL(string: remoteURLString) else {
									parseURLSuccess = false
									break
							}
							remoteURLs.append(remoteURL)
						}
					}
					
					guard parseURLSuccess else {
						completion(.failure(NSError.defaultGXError(withDeveloperDescription: "DiagnosisKeyDataProviderResult invalid format.")))
						return
					}
					
					completion(.success(remoteURLs))
				}
			}
		}
	}
	
	// MARK - Background Task
	
	private static let backgroundTaskIdentifier = Bundle.main.bundleIdentifier! + ".exposure-notification"
	
	private static func registerDetectionBackgroundTask() {
		BGTaskScheduler.shared.register(forTaskWithIdentifier: backgroundTaskIdentifier, using: .main) { task in
			let progress = shared.detectExposures { success in
				task.setTaskCompleted(success: success)
			}
			
			// Handle running out of time
			task.expirationHandler = {
				progress.cancel()
				let logService = GXLog.loggerService()
				if logService.isLogEnabled {
					logService.logMessage("Background exposure detection session ran out of background time.", for: .general, with: .warning, logToConsole: false)
				}
			}
		}
	}
	
	private static func scheduleBackgroundTaskIfNeeded(earliestBeginDate: Date? = nil, verifyPendingTasks: Bool = true, completion: (() -> Void)? = nil) {
		guard ENManager.authorizationStatus == .authorized,
			GXExposureAlertsLocalStorage.shared.lastStartedExposureConfiguration != nil else {
				completion?()
				return
		}
		
		var minEarliestBeginDate = earliestBeginDate
		let minInterval = GXExposureAlertsLocalStorage.shared.exposureDetectionMinInterval
		if minInterval > 0, let lastSession = GXExposureAlertsLocalStorage.shared.lastExposureDetectionResult?.sessionTimeStamp {
			let candidateEarliestBeginDate = lastSession.addingTimeInterval(TimeInterval(minInterval * 60))
			if minEarliestBeginDate == nil || minEarliestBeginDate!.after(candidateEarliestBeginDate) {
				minEarliestBeginDate = candidateEarliestBeginDate
			}
		}
		if minEarliestBeginDate?.before(Date()) ?? false {
			minEarliestBeginDate = nil
		}
		
		func addTask() {
			let taskRequest = BGProcessingTaskRequest(identifier: backgroundTaskIdentifier)
			taskRequest.requiresNetworkConnectivity = true
			taskRequest.earliestBeginDate = minEarliestBeginDate
			let logService = GXLog.loggerService()
			do {
				try BGTaskScheduler.shared.submit(taskRequest)
				if logService.isLogEnabled {
					logService.logMessage("Exposure detection background task scheduled for \(minEarliestBeginDate != nil ? minEarliestBeginDate! as Any : "as soon as possible")", for: .general, with: .debug, logToConsole: true)
				}
			} catch {
				if logService.isLogEnabled {
					logService.logMessage("Unable to schedule background task: \(error)", for: .general, with: .error, logToConsole: true)
				}
			}
		}
		
		if verifyPendingTasks {
			BGTaskScheduler.shared.getPendingTaskRequests { (tasks) in
				if let pendingTask = tasks.first(where: { $0.identifier == backgroundTaskIdentifier }) {
					if let pTaskEarliestBeginDate = pendingTask.earliestBeginDate,
						minEarliestBeginDate == nil || minEarliestBeginDate!.before(pTaskEarliestBeginDate)
					{
						addTask()
					}
				}
				else {
					addTask()
				}
				completion?()
			}
		}
		else {
			addTask()
			completion?()
		}
	}
	
	private static func cancelPendingBackgroundTask() {
		BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: backgroundTaskIdentifier)
	}
}


@available(iOS 13.5, *)
extension GXExposureAlertsLocalStorage.ExposureConfiguration {
	
	public init(enConfig: ENExposureConfiguration) {
		self.init(minimumRiskScore: UInt16(enConfig.minimumRiskScore),
				  attenuationLevelValues: enConfig.attenuationLevelValues.map { $0.uint8Value },
				  attenuationWeight: enConfig.attenuationWeight,
				  daysSinceLastExposureLevelValues: enConfig.daysSinceLastExposureLevelValues.map { $0.uint8Value },
				  daysSinceLastExposureWeight: enConfig.daysSinceLastExposureWeight,
				  durationLevelValues: enConfig.durationLevelValues.map { $0.uint8Value },
				  durationWeight: enConfig.durationWeight,
				  transmissionRiskLevelValues: enConfig.transmissionRiskLevelValues.map { $0.uint8Value },
				  transmissionRiskWeight: enConfig.transmissionRiskWeight)
	}
	
	public func createENExposureConfiguration() -> ENExposureConfiguration {
		let enConfig = ENExposureConfiguration()
		enConfig.minimumRiskScore = ENRiskScore(min(minimumRiskScore, UInt16(ENRiskScore.max)))
		enConfig.attenuationLevelValues = attenuationLevelValues.map { NSNumber(value: $0) }
		enConfig.attenuationWeight = attenuationWeight
		enConfig.daysSinceLastExposureLevelValues = daysSinceLastExposureLevelValues.map { NSNumber(value: $0) }
		enConfig.daysSinceLastExposureWeight = daysSinceLastExposureWeight
		enConfig.durationLevelValues = durationLevelValues.map { NSNumber(value: $0) }
		enConfig.durationWeight = durationWeight
		enConfig.transmissionRiskLevelValues = transmissionRiskLevelValues.map { NSNumber(value: $0) }
		enConfig.transmissionRiskWeight = transmissionRiskWeight
		return enConfig
	}
}
