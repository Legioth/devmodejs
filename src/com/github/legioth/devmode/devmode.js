console.log('load', window, undefined);
window.installDevMode = function(target) {
	if (target.__gwt_HostedModePlugin === undefined) {
		$wnd = target;
		(function() {
			// Function for sending a message to the server, created by init
			var sendMessage;
			
			// "Maps" java object instances to an id
			// TODO should probably use {} with string keys instead
			var objects = [];
			
			var getOrCreateObject = function(id) {
				if (!(id in objects)) {
					var value;
					// Java object id1 is the special dispatcher object
					if (id == 1) {
						value = function(dispId, thisObj) {
							var args = [];
							for(var i = 2; i < arguments.length; i++) {
								args[i - 2] = convertFromJs(arguments[i]);
							}
							var response = sendMessage([0, dispId, convertFromJs(thisObj), args]);
							return processMessages(response);
						}					
					} else {
						value = {};
					}
					value.__gwt_JavaObjectId = id;
					objects[id] = new Proxy(value, {
						get: function(target, name) {
							if (name in target) {
								// Mainly for __gwt_JavaObjectId
						        return target[name];
							} else if (parseInt(name) == name) {
								//invoke special, get field, 2 arguments, refId, dispId
								var response = sendMessage([5 , 2, 2, id, parseInt(name)]);
								var result = processMessages(response);
								if (result[0]) {
									throw result[1];
								} else {
									return result[1];
								}
							}
						},
						set: function(target, name, value) {
							if (parseInt(name) == name) {
								//invoke special, set field, 3 arguments, refId, dispId, value
								var response = sendMessage([5, 3, 3, id, parseInt(name), convertFromJs(value)]);
								var result = processMessages(response);
								if (result[0]) {
									throw result[1];
								} else {
									return result[1];
								}
							}
						}
					});
				}
				return objects[id];
			}
			
			var sendResponse = function(responseData) {
				var value = convertFromJs(responseData[1]);
				// response, isException, data
				var result = sendMessage([1, responseData[0], value]);
				return processMessages(result);
			}
			
			// Map jso objects to ids
			// TODO should probably use {} with string keys instead
			var jsoObjects = [];
			
			var ensureJSOid = function(value) {
				var id = value.__gwt_ObjectId;
				if (id === undefined) {
					id = jsoObjects.length;
					jsoObjects[id] = value;
					Object.defineProperty(value, '__gwt_ObjectId', {value: id});
				}
				return id;
			}
			
			var convertFromJs = function(value) {
				// TODO Use switch(typeof value) for the common cases
				if (value === undefined) {
					return [12];
				} else if (value === null) {
					return [0];
				} else if (typeof value == 'boolean') {
					return [1, value];
				} else if (typeof value == 'number') {
					return [8, value];
				} else if (typeof value == 'string' || value instanceof String) {
					//TODO should might need to support String prototype from other window as well 
					return [9, value.toString()];
				} else if (typeof value == 'object' || typeof value == 'function') {
					if ('__gwt_JavaObjectId' in value) {
						var id = value.__gwt_JavaObjectId;
						// Sanity check!
						if (objects[id] != value) {
							console.error(value, 'not found in', objects);
						}
						return [10, id];
					}
	
					return [11, ensureJSOid(value)];
				} else {
					console.error("Unsupported value", typeof value, value);
				}
			}
			
			var convertToJs = function(value) {
				switch (value[0]) {
					case 0:
						//Null
						return null;
					case 1:
						//boolean
						return !!value[1];
					case 5:
						//int
						//TODO rounding?
						return Number(value[1]);
					case 8:
						//double
						return Number(value[1]);
					case 9:
						//String
						return String(value[1]);
					case 10:
						//JavaObjectRef
						var id = value[1];
						return getOrCreateObject(id);
					case 11:
						//JSO reference
						var id = value[1];
						var obj = jsoObjects[id];
						if (obj === undefined) {
							console.error('requested JSO', id, 'not found in ', jsoObjects);
						}
						return obj;
					case 12:
						//undefined
						return undefined;
					default:
						throw console.log("Unsupported value type", value);
				}
			}
			
			var loadJsni = function(message) {
				var jsni = message[1];
				target.eval(jsni);
			}
			
			var invoke = function(message) {
				var name = message[1];
				var thisRef = convertToJs(message[2]);
				var args = message[3];
				var jsArgs = [thisRef, name];
				var argLength = args.length;
				for(var i = 0; i < argLength; i++) {
					var jsArg = convertToJs(args[i]);
					jsArgs[i + 2] = jsArg;
				}
				var response = target.__gwt_jsInvoke.apply(target, jsArgs);
				if (response[0] == 1) {
					console.error('Got exception when invoking ', message, ': ', response[1]);
				}
				return sendResponse(response);
			}
			
			var processReturn = function(message) {
				var isException = !!message[1];
				var returnValue = convertToJs(message[2]);
				return [isException, returnValue];
			}
			
			var free = function(message) {
				var ids = message[1];
				for(var i = 0; i < ids.lenght; i++) {
					jsoObjects[ids[i]] = null;
				}
			}
			
			var processMessages = function(messages) {
				try {
					for(var i = 0; i < messages.length; i++) {
						var message = messages[i]
						var type = message[0];
						switch(type) {
							case 4:
								loadJsni(message);
								break;
							case 0:
								if (i != messages.length - 1) {
									throw "Can't handle invoke if it isn't the last message";
								}
								return invoke(message);
							case 6:
								free(message);
								break;
							case 1:
								if (i != messages.length - 1) {
									throw "Can't handle return if it isn't the last message";
								}
								return processReturn(message);
							default:
								throw "Unsupported message type " + type;
						}
					}
				} catch (e) {
					console.error(e);
					throw e;
				} 
			} 
			
			target.__gwt_HostedModePlugin = {
					init: function() {
						console.log('plugin init', arguments);
						return true;
					},
					connect: function(url, sessionid, host, module, hostVersion) {
						console.log('plugin connect', arguments);
						var sendFailed = false;
						sendMessage = function(data) {
							if (sendFailed) {
								throw 'Connection has failed';
							}
							var request = new XMLHttpRequest();
							request.open('POST', 'http://'+host + '/?GwtSession=' + encodeURIComponent(window.top.__gwt_SessionID), false);
							try {
								request.send(JSON.stringify(data));
							} catch (e) {
								sendFailed = true;
								throw e;
							}
							 
							if (request.status === 200) {
							  var response = request.responseText;
							  var responseJson = JSON.parse(response);
							  return responseJson;
							} else {
								sendFailed = true;
								console.error('Did not get 200 respone',request.status, request);
								throw 'Got ' + request.status + ' response';
							}
						}
						
						target.setTimeout(function() {
							//check version, min 3, max 3
							var versionMessage = sendMessage([8, 3 , 3, hostVersion]);
							if (versionMessage[0] != 9) {
								console.error("Expected message type 9, got " + versionMessage[0]);
								return;
							}
							if (versionMessage[1] != 3) {
								console.error("Expected protocol version 3, got " + versionMessage[1]);
								return;
							}
							
							//LoadModule
							var loadResult = sendMessage([12, document.location.href, '', window.top.__gwt_SessionID, module, navigator.userAgent]);
							var result = processMessages(loadResult);
							console.log('LoadModule completed', result);						
						}, 0);
	
						return true;
					}
			}	
		})(); 
	}
};