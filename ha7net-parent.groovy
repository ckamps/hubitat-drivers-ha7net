def version() {'v0.1.6'}

import groovy.xml.*

metadata {
    definition (name: 'HA7Net 1-Wire - Parent',
                namespace: 'ckamps', 
                author: 'Christopher Kampmeier',
                importUrl: 'https://raw.githubusercontent.com/ckamps/hubitat-drivers-ha7net/master/ha7net-parent.groovy') {
        
        capability 'Refresh'

        command 'createChildren'
        command 'deleteChildren'
        command 'deleteUnmatchedChildren'
        command 'recreateChildren'
        command 'refreshChildren'
    }

    preferences {
        input name: 'address',   type: 'text', title: 'HA7Net Address',       description: 'FQDN or IP address', required: true
        input name: 'logEnable', type: 'bool', title: 'Enable debug logging', defaultValue: false
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()   
}

def initialize() {
    state.version = version()
}

def refresh() {
    refreshChildren()
}

def createChildren() {
    def sensors = []

    sensors = getSensors()

    sensors.each { sensorId ->
        // If we don't find a child device equal to the current sensor ID, then we'll
        // determine the sensor type, and add one or more child devices.
        if (getChildDevice(sensorId) == null) {
            sensorType = getSensorType(sensorId)
            if (sensorType == 'temperature') {
                if (logEnable) log.debug "Discovered temperature sensor: ${sensorId}"
                child = addChildDevice("ckamps", "HA7Net 1-Wire - Child - Temperature", sensorId, [name: sensorId, label: "${sensorId} - Temperature", isComponent: false])
                child.refresh()
            } else if (sensorType == 'humidity') {
                if (logEnable) log.debug "Discovered humidity sensor: ${sensorId}"
                
                child = addChildDevice("ckamps", "HA7Net 1-Wire - Child - Humidity", sensorId, [name: sensorId, label: "${sensorId} - Humidity" , isComponent: false])
                child.refresh()
                // Since AAG TAI-8540 sensors can have the same 1-Wire ID for both humidity and temp, by convention, we appended
                // a trailing ".1" to the 1-Wire ID when we registered the temperature device.
                child = addChildDevice("ckamps", "HA7Net 1-Wire - Child - Temperature (H)", "${sensorId}.1", [name:  "${sensorId}.1", label:  "${sensorId}.1 - Temperature", isComponent: false])
                child.refresh()
            } else {
                if (logEnable) log.warn "Discovered unknown sensor type: ${sensorId}"
            }
        }
    }
}

def refreshChildren(){
    if (logEnable) log.info "Refreshing children"
    def children = getChildDevices()
    children.each {child->
        child.refresh()
    }
}

def recreateChildren(){
    if (logEnable) log.info "Recreating children"
    // To Do: Based on a new preference, capture the name and label of each child device and reapply those names and labels
    // for all discovered sensors that were previously known.
    deleteChildren()
    createChildren()
}

def deleteChildren() {
    if (logEnable) log.info "Deleting children"
    def children = getChildDevices()
    children.each {child->
        deleteChildDevice(child.deviceNetworkId)
    }
}

def deleteUnmatchedChildren() {
   // To Do: Not yet implemnted.
   discoveredSensors = getSensors()
   getChildDevices().each { device ->
       if (logEnable) log.debug("Found an existing child device")
   }
}

def doHttpPost(uri, path, body) {
    def response = []
    int retries = 0
    def cmds = []
    cmds << 'delay 1000'

    // Attempt a max of 3 retries to address cases in which transient read errors can occur when 
    // interacting with the HA7Net.
    while(retries++ < 3) {
        try {
            httpPost( [uri: uri, path: path, body: body, requestContentType: 'application/x-www-form-urlencoded'] ) { resp ->
                if (resp.success) {
                    response = resp.data
                    if ((logEnable) && (response.data)) {
                        serializedDocument = XmlUtil.serialize(response)
                        log.debug(serializedDocument.replace('\n', '').replace('\r', ''))
                    }
                } else {
                    throw new Exception("httpPost() not successful for: ${uri} ${path}") 
                }
            }
            return(response)
        } catch (Exception e) {
            log.warn "httpPost() of ${path} to HA7Net failed: ${e.message}"
            // When read time out error occurs, retry the operation. Otherwise, throw
            // an exception.
            if (!e.message.contains('Read timed out')) throw new Exception("httpPost() failed for: ${uri} ${path}")
        }
        log.warn('Delaying 1 second before next httpPost() retry')
        cmds
    }
    throw new Exception("httpPost() exceeded max retries for: ${uri} ${path}")
}

// To Do: Is there a more direct means for child devices to access parent preferences/settings?
def getHa7netAddress() {
    return(address)   
}

private def getSensors() {
    def uri = "http://${address}"
    def path = '/1Wire/Search.html'
    def body = [LockID: '0']

    response = doHttpPost(uri, path, body)

    def discoveredSensors = []
    def sensorElements = []

    // We should be able to modify this findAll statement to construct an array of sensor IDs
    // as opposed to depending on the each loop.
    //
    // Something like the following but I have yet to get the right hand side correct:
    //
    // discoveredSensors = response.'**'.findAll{ it.@name.text().startsWith('Address_') }.*.value.text()

    sensorElements = response.'**'.findAll{ it.@name.text().startsWith('Address_') }
    
    if (logEnable) log.debug("number of sensor elements found: ${sensorElements.size()}")
    
    sensorElements.each {
        def sensorId = it.@value.text()
        if (logEnable) log.debug("Sensor discovered - value: ${sensorId}")
        discoveredSensors.add(sensorId)
    }

    return(discoveredSensors)
}

private def getSensorType(sensorId) {
    // Attempt to look up humidity value. If successful, assume 1-Wire sensor is a combination humidity and
    // temperture sensor. If not successful, assume temperature only sensor.
    def uri = "http://${address}"
    def path = '/1Wire/ReadHumidity.html'
    def body = [Address_Array: "${sensorId}"]

    response = doHttpPost(uri, path, body)

    element = response.'**'.find{ it.@class == 'HA7Value' &&
                                  it.@name.text().startsWith('Device_Exception_0') &&
                                  it.@value.text().startsWith('Not a')
                                 }
     
    // To Do: When we think we found a temperature only device, we should probably do a standalone temperature
    // lookup to confirm that it is a temperature device before moving on.

    // To Do: Add deteection of unsupported devices and log those cases.

    return(element ? 'temperature' : 'humidity')
}