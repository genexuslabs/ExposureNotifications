//
//  GXActionExtObjExposureNotificationHandler.swift
//

import Foundation

class GXActionExtObjExposureNotificationHandler: GXActionExternalObjectHandler {
	
	override class func handleActionExecutionUsingMethodHandlerSelectorNamePrefix() -> Bool {
		return true
	}
	
	// MARK: - Private Helpers
	
	private func setReturnValueFromAsyncHandler<T>(_ asyncHandler: (@escaping (Result<T, Error>) -> Void) -> Void) {
		asyncHandler { [weak self] (result) in
			guard let sself = self, sself.isSelfExecuting else { return }
			gx_dispatch_on_main_queue {
				switch result {
				case .success(let returnValue):
					sself.setReturnValue(returnValue)
					sself.onFinishedExecutingWithSuccess()
				case .failure(let error):
					sself.onFinishedExecutingWithError(error)
				}
			}
		}
	}
	
	private func setBoolReturnValueOrFailFromAsyncHandler(_ asyncHandler: (@escaping (Error?) -> Void) -> Void) {
		asyncHandler { [weak self] (error) in
			guard let sself = self, sself.isSelfExecuting else { return }
			gx_dispatch_on_main_queue {
				let returnValue = NSNumber(value: error == nil)
				let hasReturnValue = sself.setReturnValue(returnValue)
				if !hasReturnValue, let error = error {
					sself.onFinishedExecutingWithError(error)
				}
				else {
					sself.onFinishedExecutingWithSuccess()
				}
			}
		}
	}
	
	private func executeWithValidParameters(_ params: [Any], expected: UInt, handler: () -> Void) {
		if let error = validateNumber(ofParametersReceived: UInt(params.count), expected: expected) {
			onFinishedExecutingWithError(error)
		}
		else {
			handler()
		}
	}
	
	// MARK: - Method Handlers
	
	@objc public func gxActionExObjMethodHandler_IsAvailable() {
		setReturnValue(NSNumber(value: GXEOExposureNotification.isAvailable))
		onFinishedExecutingWithSuccess()
	}
	
	@objc public func gxActionExObjMethodHandler_Enabled() {
		GXEOExposureNotification.isEnabled { [weak self] (enabled) in
			guard let sself = self, sself.isSelfExecuting else { return }
			sself.setReturnValue(NSNumber(value: enabled))
			sself.onFinishedExecutingWithSuccess()
		}
	}
	
	@objc public func gxActionExObjMethodHandler_AuthorizationStatus() {
		setReturnValue(NSNumber(value: GXEOExposureNotification.authorizationStatus.rawValue))
		onFinishedExecutingWithSuccess()
	}
	
	@objc public func gxActionExObjMethodHandler_BluetoothEnabled() {
		GXEOExposureNotification.isBluetoothEnabled { [weak self] (enabled) in
			guard let sself = self, sself.isSelfExecuting else { return }
			sself.setReturnValue(NSNumber(value: enabled))
			sself.onFinishedExecutingWithSuccess()
		}
	}
	
	@objc public func gxActionExObjMethodHandler_LastExposureDetectionResult() {
		setReturnValue(GXEOExposureNotification.lastExposureDetectionResult)
		onFinishedExecutingWithSuccess()
	}
	
	@objc public func gxActionExObjMethodHandler_ExposureDetected() {
		setReturnValue(NSNumber(value: GXEOExposureNotification.exposureDetected))
		onFinishedExecutingWithSuccess()
	}
	
	@objc public func gxActionExObjMethodHandler_ExposureDetectionMinInterval() {
		setReturnValue(NSNumber(value: GXEOExposureNotification.exposureDetectionMinInterval))
		onFinishedExecutingWithSuccess()
	}
	
	@objc public func gxActionExObjMethodHandler_setExposureDetectionMinInterval(_ params: [Any]) {
		executeWithValidParameters(params, expected: 1) {
			if let newValue = optionalUnsignedIntegerParameter(actionDescParametersArray![0], fromValue: params[0]) {
				GXEOExposureNotification.exposureDetectionMinInterval = newValue
			}
			else {
				GXUtilities.tryHandleFatalError("Invalid \(actionExObjDesc.actionCallObjectName).\(actionExObjDesc.actionExternalObjectMethod) param value: \(params[0])")
			}
			onFinishedExecutingWithSuccess()
		}
	}
	
	@objc public func gxActionExObjMethodHandler_ExposureInformationUserExplanation(_ params: [Any]) {
		setReturnValue(GXEOExposureNotification.exposureInformationUserExplanation ?? "")
		onFinishedExecutingWithSuccess()
	}
	
	@objc public func gxActionExObjMethodHandler_setExposureInformationUserExplanation(_ params: [Any]) {
		executeWithValidParameters(params, expected: 1) {
			let newValue = stringParameter(actionDescParametersArray![0], fromValue: params[0])
			GXEOExposureNotification.exposureInformationUserExplanation = newValue
			onFinishedExecutingWithSuccess()
		}
	}
		
	@objc public func gxActionExObjMethodHandler_Start(_ params: [Any]) {
		executeWithValidParameters(params, expected: 1) {
			if let config = sdtParameter(actionDescParametersArray![0], fromValue: params[0]) {
				setBoolReturnValueOrFailFromAsyncHandler { (completion) in
					GXEOExposureNotification.start(exposureConfiguration: config, completion: completion)
				}
			}
			else {
				let error = GXUtilities.tryHandleFatalError("Invalid ExposureConfiguration parameter in \(self.actionExObjDesc.actionExternalObjectMethod)")
				onFinishedExecutingWithError(error)
			}
		}
	}
	
	@objc public func gxActionExObjMethodHandler_Stop() {
		setBoolReturnValueOrFailFromAsyncHandler(GXEOExposureNotification.stop(completion:))
	}
	
	@objc public func gxActionExObjMethodHandler_GetTemporaryExposureKeyHistoryForSharing() {
		setReturnValueFromAsyncHandler(GXEOExposureNotification.temporaryExposureKeyHistoryForSharing(completion:))
	}
	
	@objc public func gxActionExObjMethodHandler_GetLastExposureDetectionSessionDetails() {
		setReturnValueFromAsyncHandler(GXEOExposureNotification.lastExposureDetectionSessionDetails(completion:))
	}
	
	@objc public func gxActionExObjMethodHandler_ResetLastExposureDetectionResult() {
		setBoolReturnValueOrFailFromAsyncHandler(GXEOExposureNotification.resetLastExposureDetectionResult(completion:))
	}

	#if DEBUG
	@objc public func gxActionExObjMethodHandler_StartDetectionSession() {
		if #available(iOS 13.5, *) {
			_ = GXExposureAlertsManager.shared.detectExposures()
			onFinishedExecutingWithSuccess()
		} else {
			onFinishedExecutingWithError(GXEOExposureNotification.notAvailableError)
		}
	}
	#endif
}
