/* =============================================================================
Hubitat Elevation Application
SprinklerSchedulePlus (Monolithic)

    Inspiration: Lighting Schedules https://github.com/matt-hammond-001/hubitat-code
    Inspiration: github example from Hubitat of lightsUsage.groovy
    Derived from: Sprinkler Schedules https://github.com/csteele-PD/Hubitat-public/tree/master/SprinklerSchedule
	Code location: https://github.com/relifram/Hubitat-public/tree/master/SprinklerSchedule

-----------------------------------------------------------------------------
This code is licensed as follows:

	Portions:
	 	Copyright (c) 2022 Hubitat, Inc.  All Rights Reserved Bruce Ravenel 

	BSD 3-Clause License
	
	Copyright (c) 2026, J Haubold
	Copyright (c) 2023, C Steele
	Copyright (c) 2020, Matt Hammond
	All rights reserved.
	
	Redistribution and use in source and binary forms, with or without
	modification, are permitted provided that the following conditions are met:
	
	1. Redistributions of source code must retain the above copyright notice, this
	   list of conditions and the following disclaimer.
	
	2. Redistributions in binary form must reproduce the above copyright notice,
	   this list of conditions and the following disclaimer in the documentation
	   and/or other materials provided with the distribution.
	
	3. Neither the name of the copyright holder nor the names of its
	   contributors may be used to endorse or promote products derived from
	   this software without specific prior written permission.
	
	THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
	AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
	IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
	DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
	FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
	DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
	SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
	CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
	OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
	OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
-----------------------------------------------------------------------------
 * Version 2.0.0: Monolithic architecture consolidation. Unified parent/child topologies.
 * Includes explicit UX chain validation, isolated array payload scoping, and sequential 
 * synchronous execution mapping for real-time operation.
 */

public static String version() { return "v2.0.0" }

definition(
    name: "SprinklerSchedulePlus",
    namespace: "relifram",
    author: "relifram",
    description: "Monolithic controller for switching valves/relays to a timing schedule",
    importUrl: "",
    documentationLink: "",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "schedulePage")
    page(name: "environmentPage")
}

def mainPage() {
    init(1) 
    dynamicPage(name: "mainPage", title: "", uninstall: true, install: true) {
        updateMyLabel(1)
        displayHeader()
        state.appInstalled = app.getInstallationState() 
        if (state.appInstalled != 'COMPLETE') return installCheck() 

        section(menuHeader("General Settings")) {
            label title: "<b>Name for this application</b>", required: false, submitOnChange: true
            if (app.label.contains('<span ')) {
                String myLabel = app.label.substring(0, app.label.indexOf('<span '))
                atomicState.appDisplayName = myLabel
                app.updateLabel(myLabel)
            }

            paragraph ""
            input "schEnable", "bool", title: "<b>Schedule Active?</b>", required: false, defaultValue: true, submitOnChange: true
            state.paused = schEnable ? false : true

            paragraph "\n<b>Switch Select</b>"
            input "valves",
                "capability.switch",
                title: "Control which valve switches?",
                multiple: true,
                required: false,
                submitOnChange: true
        }

        if (schEnable && valves) {
            section(menuHeader("Configuration Routing")) {
                href "schedulePage", title: "Timetable Matrix", description: "Configure Day Groups, durations, and map switches.", state: "complete"
                href "environmentPage", title: "Global Overrides & Logging", description: "Configure seasonal adjustments, rain holds, temperature limits, and logs.", state: "complete"
            }

            section(menuHeader("System Status")) {
                currentMonth = new Date().format("M") 	
                currentMonthPercentage = state.month2month ? state.month2month[currentMonth].toDouble() : 1  
                 
                String str = "<div style='background-color: rgba(73, 163, 125, 0.3);'>"	
                if (state.month2month) {
                    str += "<b>Adjust valve timing</b> by Month is active. Current month is: <b>$currentMonthPercentage%</b><br>" +
                    "<b>Rain hold</b> is $state.rainHold<br>" +
                    "<b>Soil</b> is $state.defaultSoilType<br>"
                }
                if (state.overTempToday) { str += "Sometime today, the outside temperature <b>exceeded</b> the limit you set of $state.maxOutdoorTemp and any Over Temp schedules <b>will run.</b><br>" }
                str += valves?.collect { dev -> "<b>${dev.label ?: dev.name}</b> is ${dev.currentValue('switch') == 'on' ? 'On' : 'Off'}"}?.join(', ') ?: ""
                str += "</div>"
                paragraph str
            }
        }
    }
}

def schedulePage() {
    dynamicPage(name: "schedulePage", title: "Schedule Matrix", uninstall: false, install: false) {
        displayHeader()
        section(menuHeader("Schedule Matrix")) {
            paragraph "<b>Select Days into Groups</b>"
            paragraph displayDayGroups()		
            displayDuration()
            displayStartTime()

            paragraph "<b>Select Switches into Day Groups</b>"
            paragraph displayGrpSched()		
            selectDayGroup()

            paragraph "\n<hr style='background-color:#1A77C9; height: 1px; border: 0;'></hr>"
        }
    }
}

def environmentPage() {
    dynamicPage(name: "environmentPage", title: "Global Overrides & Logging", uninstall: false, install: false) {
        displayHeader()
        section(menuHeader("Global Overrides & Environment")) {
            paragraph displayMonths() 
            editMonths()
            selectTemperatureDevice()
            selectRainDevice()
			
			paragraph "\n<b>Soil Type Data (Information Only)</b>"
            paragraph "Current Saved Soil: <b>${state.defaultSoilType ?: 'Unknown'}</b>"
            input "btnUsda", "button", title: "Fetch USDA Soil Data", width: 3, submitOnChange: true
        }

        if (state.rainDeviceData != [:]) {
            section(menuHeader("Rain Sensor Mapping")) {
                def rainVars = ["0": "no rainHold"]
                state.rainDeviceData.each { key, info ->
                    rainVars[key] = info.name
                }
                input "rainEnableDevice", "enum", 
                    title: "<b>Choose the Rain Sensor for this Timetable</b><p><i>leave unselected for no Rain Hold</i></p>", 
                    submitOnChange: true, 
                    defaultValue: "0",
                    options: rainVars
            }
        }

        section(menuHeader("System Logging")) {
            input "infoEnable", "bool", title: "Enable activity logging", required: false, defaultValue: true, width: 2
            input "debugEnable", "bool", title: "Enable debug logging", required: false, defaultValue: false, submitOnChange: true, width: 2

            if (debugEnable) {
                input "debugTimeout", "enum", required: false, defaultValue: "0", title: "Automatic debug Log Disable Timeout?", width: 3,  \
                        options: [ "0":"None", "1800":"30 Minutes", "3600":"60 Minutes", "86400":"1 Day" ]
            }
        }
    }
}

// -----------------------------------------------------------------------------
// UI Rendering & Matrix Management
// -----------------------------------------------------------------------------

String displayDayGroups() {
    // Process pending duration save
    if (state.duraTimeBtn && settings.DuraTime != null) {
        def nIndex = state.duraTimeBtn.toString()
        if (state.dayGroup.containsKey(nIndex)) { state.dayGroup[nIndex].duraTime = settings.DuraTime }
        state.remove("duraTimeBtn")
        app.removeSetting("DuraTime")
    }

    // Process pending StartTime save & Chain Collision Logic
    if (state.startTimeBtn) {
        def mode = settings.StartMode ?: "time"
        if ((mode == "time" && settings.StartTime) || (mode == "after" && settings.StartAfter)) {
            def nIndex = state.startTimeBtn.toString()
            def newVal = mode == "time" ? Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSX", settings.StartTime).format('HH:mm') : settings.StartAfter
            
            boolean hasConflict = false
            if (mode == "after") {
                def conflict = state.dayGroup.find { k, v -> v.startTime == newVal && k.toString() != nIndex }
                if (conflict) {
                    hasConflict = true
                    state.chainError = "<b>Error:</b> Day Group ${conflict.key} is already configured to follow Group ${newVal.replace('after_', '')}. Multiple groups cannot follow a single group. Please select a different trigger."
                }
            }

            if (!hasConflict) {
                state.remove("chainError")
                if (state.dayGroup.containsKey(nIndex)) { state.dayGroup[nIndex].startTime = newVal }
                state.remove("startTimeBtn")
                app.removeSetting("StartTime")
                app.removeSetting("StartMode")
                app.removeSetting("StartAfter")
            }
        }
    }

    // Button state toggles
    if (state.dayGroupBtn) {
        def btnStr = state.dayGroupBtn.toString()
        def len = btnStr.length()
        def dgK = btnStr.substring(0, len - 1)
        def dgI = btnStr.substring(len - 1)
        if (state.dayGroup.containsKey(dgK)) { state.dayGroup[dgK][dgI] = !state.dayGroup[dgK][dgI] }
        state.remove("dayGroupBtn")
        logDebug {"displayDayGroups Item: $dgK.$dgI"}
    }
    
    if (state.overTempBtn) {
        def dgK = state.overTempBtn.toString()
        if (state.dayGroup.containsKey(dgK)) { state.dayGroup[dgK].ot = !state.dayGroup[dgK].ot }
        state.remove("overTempBtn")
    }

    if (state.eraseTime) {
        def nIndex = state.eraseTime.toString()
        if (state.dayGroup.containsKey(nIndex)) { 
            state.dayGroup[nIndex].startTime = null
            state.dayGroup[nIndex].duraTime = null
        }
        state.remove("eraseTime")
        app.removeSetting("eraseTime")
        paragraph "<script>{changeSubmit(this)}</script>"
    }

    String str = "<script src='https://code.iconify.design/iconify-icon/1.0.0/iconify-icon.min.js'></script>"
    str += "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .tstat-col td,.tstat-col th { padding:8px 8px;text-align:center;font-size:12px} .tstat-col td {font-size:15px }" +
        "</style><div style='overflow-x:auto'><table class='mdl-data-table tstat-col' style=';border:2px solid black'>" +
        "<thead><tr style='border-bottom:2px solid black'>" +
        "<th style='border-right:2px solid black'>Day Group</th>" +
        "<th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th>" +
        "<th>&nbsp;&nbsp;</th>" +
        "<th colspan=2 style='color:#db7321;'>OverTemp</th>" +
        "<th>Start Time</th><th>Duration</th><th>Reset</th>" +
        "</tr></thead><tr style='color:black'border = 1>" 

    String X = "<i class='he-checkbox-checked'></i>"
    String O = "<i class='he-checkbox-unchecked'></i>"
    String Plus = "<i class='ic--sharp-plus'>+</i>"
    String addDayGroupBtn = buttonLink("addDGBtn", Plus, "#1A77C9", "")

    String strRows = ""
    state.dayGroup.each { k, dg -> 
        str += strRows
        str += "<th>$k</th>"
        for (int r = 1; r < 8; r++) { 
            String dayBoxN = buttonLink("w$k$r", O, "#1A77C9", "")
            String dayBoxY = buttonLink("w$k$r", X, "#1A77C9", "")
            str += (dg."$r") ? "<th>$dayBoxY</th>" : "<th>$dayBoxN</th>" 
        }
        String remDayGroupBtn = buttonLink("rem$k", "<i style=\"font-size:1.125rem\" class=\"material-icons he-bin\"></i>", "#1A77C9", "")
        str += "<th>$remDayGroupBtn</th>"
        
        String otBoxN = buttonLink("o$k", O, "#db7321", "")
        String otBoxY = buttonLink("o$k", X, "#db7321", "")
        str += (dg."ot") ? "<th>$otBoxY</th>" : "<th>$otBoxN</th>" 
        
        String rawTime = state.dayGroup[k]?.startTime
        String sTimeDisp = rawTime ? (rawTime.startsWith("after_") ? "After Grp ${rawTime.split('_')[1]}" : rawTime) : "Set Time"
        String sTime = rawTime ? buttonLink("t$k", sTimeDisp, "black") : buttonLink("t$k", "Set Time", "green")
        String dTime = state.dayGroup[k]?.duraTime 
        String duraTime = dTime ? buttonLink("n$k", dTime, "purple") : buttonLink("n$k", "Select", "green")
        
        String reset = buttonLink("x$k", "<iconify-icon icon='bx:reset'></iconify-icon>", "black", "20px")
        str += "<th>$sTime</th><th>$duraTime</th><th title='Reset $k' style='padding:0px 0px'>$reset</th>"
        strRows = "</tr><tr>" 
    }
    str += "</tr><tr>"
    str += "<th>$addDayGroupBtn</th><th colspan=4> <- Add new Day Group</th><th colspan=8>&nbsp;</th>"
    str += "</tr></table></div>"
    return str
}

def displayStartTime() {
    if(state.startTimeBtn) {
        def startTimeBtn = state.startTimeBtn.toString()
        def currentVal = state.dayGroup[startTimeBtn]?.startTime
        def isAfter = currentVal?.toString()?.startsWith("after_")
        def effectiveMode = settings.StartMode ?: (isAfter ? "after" : "time")

        input "StartMode", "enum", title: "Start Trigger", submitOnChange: true, width: 3, options: ["time":"Scheduled Time", "after":"After Day Group"], defaultValue: isAfter ? "after" : "time"
        
        if (effectiveMode == "after") {
            def groupOpts = [:]
            state.dayGroup.each { k, v -> if (k.toString() != startTimeBtn) groupOpts["after_${k}"] = "Day Group ${k}" }
            input "StartAfter", "enum", title: "Select Group", submitOnChange: true, width: 3, options: groupOpts, defaultValue: isAfter ? currentVal : null, newLineAfter: false
            
            if (state.chainError) {
                paragraph "<div style='color:red; padding-top: 4px'>${state.chainError}</div>"
            }
        } else {
            input "StartTime", "time", title: "At This Time", submitOnChange: true, width: 3, defaultValue: isAfter ? null : currentVal, newLineAfter: false
        }
        input "DoneTime$startTimeBtn", "button", title: "  Done with time  ", width: 2, newLineAfter: true
    }
}

def displayDuration() {
    if(state.duraTimeBtn) {
        def duraTimeBtn = state.duraTimeBtn.toString()
        def currentVal = state.dayGroup[duraTimeBtn]?.duraTime
        input "DuraTime", "decimal", title: "Sprinkler Duration", submitOnChange: true, width: 4, range: "0..60", defaultValue: currentVal, newLineAfter: true
    }
}

String displayGrpSched() {
    String str = "<script src='https://code.iconify.design/iconify-icon/1.0.0/iconify-icon.min.js'></script>"
    str += "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .tstat-col td,.tstat-col th { padding:8px 8px;text-align:center;font-size:12px} .tstat-col td {font-size:15px }" +
        "</style><div style='overflow-x:auto'><table class='mdl-data-table tstat-col' style=';border:2px solid black'>" +
        "<thead><tr style='border-bottom:2px solid black'>" +
        "<th style='border-right:2px solid black'>Valve</th>" +
        "<th>Day Group</th>" +
        "</tr></thead>"

    state.valves.keySet().findAll { k -> !(k in valves.id) }.each { k -> state.valves.remove(k) }
    valves?.sort{it.displayName.toLowerCase()}.each { dev ->
        String devLink = "<a href='/device/edit/$dev.id' target='_blank' title='Open Device Page for $dev'>$dev"
        String myDG = state.valves[dev.id].dayGroup.join(', ')
        String myDayGroup = myDG ? buttonLink("r$dev.id", myDG, "purple") : buttonLink("r$dev.id", "Select", "green")
        str += "<tr style='color:black'><td style='border-right:2px solid black'>$devLink</td>" +
            "<td title='${myDG ? "Deselect $myDG" : "Select String Hub Variable"}'>$myDayGroup</td></tr>"
    }  
    str += "</table></div>"
    return str
}

def selectDayGroup() {
    if(state.dayGrpBtn) {
        List vars = state.dayGroup.keySet().collect() 
        input "DayGroup", "enum", title: "Sprinkler Group", submitOnChange: true, width: 4, options: vars, newLineAfter: true, multiple: true
        if(DayGroup) {
            state.valves[state.dayGrpBtn].dayGroup = DayGroup
            state.remove("dayGrpBtn")
            app.removeSetting("DayGroup")
            paragraph "<script>{changeSubmit(this)}</script>"
        }
    }
}

def addDayGroup(evt = null) {
    def dayGroupTemplate = [ '1': false, '2': false, '3': false, '4': false, '5': false, '6': false, '7': false, "s": "P", "name": "", "ot": false, "ra": false, "duraTime": null, "startTime": null ]
    def newIndex = (state.dayGroup.size() + 1).toString() 
    state.dayGroup[newIndex] = dayGroupTemplate.clone()
}

def remDayGroup(evt = null) {
    if (state.dayGroup.size() > 1) {
        def keyToDelete = evt.toString()
        if (state.dayGroup.containsKey(keyToDelete)) { state.dayGroup.remove(keyToDelete) } 
        def dayGrpReOrder = [:]
        def counter = 1
        state.dayGroup.sort { it.key.toInteger() }.each { k, v -> dayGrpReOrder["${counter++}"] = v }
        state.dayGroup = dayGrpReOrder
    }
}

// -----------------------------------------------------------------------------
// Global Variable Rendering & Processing
// -----------------------------------------------------------------------------

String displayMonths() {
    String str = "<i>Assume that Valve Duration is 100% and adjust that timing by these percentages, monthly. Valve Duration is reduced to the percentage defined for the month in which it runs. (20 seconds is the valve's minimum duration.) </i><p>"
    str += "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .tstat-col td,.tstat-col th { padding:8px 8px;text-align:center;font-size:12px} .tstat-col td {font-size:15px }" +
        "</style><div style='overflow-x:auto'><table class='mdl-data-table tstat-col' style=';border:2px solid black'>" +
        "<thead><tr style='border-bottom:2px solid black'><th>Jan</th><th>Feb</th><th>Mar</th><th>Apr</th><th>May</th><th>Jun</th><th>Jul</th><th>Aug</th><th>Sep</th><th>Oct</th><th>Nov</th><th>Dec</th></tr></thead>"
    str += "<tr style='color:black'border = 1>" 
    state.month2month.keySet().sort { it.toInteger() }.each { key ->
        String mCol = buttonLink("m${key}", "${state.month2month[key]}", "purple")
        str += "<th>$mCol</th>"
    }
    str += "</tr></table></div>"
    return str
}

def editMonths() {
    if (state.dispMonthBtn) {
        input "monthPercentage", "decimal", title: "Monthly Percentage", submitOnChange: true, width: 4, range: "1..100", defaultValue: state.month2month[state.dispMonthBtn]
        if(monthPercentage) {
            state.month2month[state.dispMonthBtn] = monthPercentage
            state.remove("dispMonthBtn")
            app.removeSetting("monthPercentage")
            paragraph "<script>{changeSubmit(this)}</script>"
        }
    }
}

def selectTemperatureDevice() {
    paragraph "\n<b>Temperature Override</b>"
    input "outdoorTempDevice", "capability.temperatureMeasurement", title: "Select which device?", multiple: false, required: false, submitOnChange: true
    input "maxOutdoorTemp", "number", title: "<i>Enter the Maximum temperature, beyond which, conditional Timetables will be invoked.</i>", defaultValue: maxOutdoorTemp, multiple: false, required: false, submitOnChange: true
}

def selectRainDevice() {
    paragraph "\n<b>Rain Device Selection</b>"
    input "rainDeviceOutdoor", "capability.waterSensor", title: "Select which device?", multiple: true, required: false, submitOnChange: true
    if (rainDeviceOutdoor) {
        def vars = [:]
        def c1=1
        def atts = rainDeviceOutdoor?.collectMany { c2 -> c2.supportedAttributes.collect { it?.toString()?.toLowerCase() } }?.toSet()?.sort()
        atts.each { v -> vars[c1++] = "$v" }
        input "selectRainAttribute", "enum", options: vars, title: "<i>Which Attribute indicates there was enough rain to skip a cycle?</i>", defaultValue: selectRainAttribute, multiple: false, required: false, submitOnChange: true
        state.currentRainAttribute = vars[selectRainAttribute as Integer]
    }	
}

// -----------------------------------------------------------------------------
// Core System Utilities & Hubitat Events
// -----------------------------------------------------------------------------

def installed() {
    logInfo {"Installed with settings."}
    initialize()
}

def updated() {
    logDebug {"updated()"}
    unschedule()
    if (debugEnable && debugTimeout.toInteger() > 0) runIn(debugTimeout.toInteger(), logsOff)
    
    unsubscribe()
    
    // Temperature Evaluation & Subscription
    if (outdoorTempDevice) { 
        subscribe(outdoorTempDevice, "temperature", "recvOutdoorTempHandler")
		
        def tempNowStr = outdoorTempDevice.currentValue("temperature")?.toString()
        def maxTempStr = settings.maxOutdoorTemp?.toString()
        
        // Auto-fill max temperature with current temperature if user left it blank
        if (tempNowStr?.isNumber() && !maxTempStr?.isNumber()) {
            logInfo {"No Maximum Temperature specified. Defaulting to current ambient temperature: ${tempNowStr}°"}
            app.updateSetting("maxOutdoorTemp", [value: tempNowStr, type: "number"])
            maxTempStr = tempNowStr
        }
        
        // Immediate baseline evaluation
        if (tempNowStr?.isNumber() && maxTempStr?.isNumber()) {
            state.overTempToday = new BigDecimal(tempNowStr) > new BigDecimal(maxTempStr)
            logDebug {"Initial OutdoorTemp evaluation. Current: ${tempNowStr}, Max: ${maxTempStr}. overTempToday: ${state.overTempToday}"}
        } else {
            state.overTempToday = false
        }
    } else {
        state.overTempToday = false
    }
    
    // Rain Sensor Evaluation & Subscription
    state.rainDeviceData = [:]
    def rainAttr = state.currentRainAttribute?.toString()
    
    if (settings.rainDeviceOutdoor && rainAttr) {
        settings.rainDeviceOutdoor.each { ard -> 
            def ms1 = ard.label ?: ard.name
            def devId = ard.id.toString()
            state.rainDeviceData[devId] = [value: ard.currentValue(rainAttr)?.toString(), name: ms1]
            subscribe(ard, rainAttr, "recvOutdoorRainHandler")
        }
        
        def activeRainDev = settings.rainEnableDevice?.toString()
        if (activeRainDev && activeRainDev != "0") {
            state.rainHold = state.rainDeviceData[activeRainDev]?.value?.toLowerCase() == "wet"
            logDebug {"Initial OutdoorRain evaluation. rainHold: ${state.rainHold}"}
        } else {
            state.rainHold = false
        }
    } else {
        state.rainHold = false
    }

    //getSoilTypeFromUSDA()
	
    updateMyLabel(2)
    scheduleNext()
}

def initialize() {
    // Retained for Hubitat framework consistency
}

def init(why) {
    switch(why) {            
        case 1: 
            if (!app.label) {
                app.updateLabel(app.name)
                atomicState.appDisplayName = app.name
            }
            if(state.valves == null) state.valves = [:] 
            if(state.paused == null) state.paused = false 
            if(state.inCycle == null) state.inCycle = false
            if(state.overTempToday == null) state.overTempToday = false 
            if(state.rainHold == null) state.rainHold = false
            if(state.rainDeviceData == null) state.rainDeviceData = [:] 
            if(state.defaultSoilType == null) state.defaultSoilType = "Unknown"
            
            if(state.month2month == null) state.month2month = ["1":"100", "2":"100", "3":"100", "4":"100", "5":"100", "6":"100", "7":"100", "8":"100", "9":"100", "10":"100", "11":"100", "12":"100"]
            if(state.dayGroup == null) state.dayGroup = ['1': ['1':true, '2':true, '3':true, '4':true, '5':true, '6':true, '7':true, "s": "P", "name": "", "ot": false, "ra": false, "duraTime": null, "startTime": null ] ] 
            
            valves?.each { dev -> if(!state.valves["$dev.id"]) { state.valves["$dev.id"] = ['dayGroup':['1']] } } 
            break; 
    }
}

void appButtonHandler(btn) {
    state.remove("dayGroupBtn")
    state.remove("dayGrpBtn")
    state.remove("doneTime")
    state.remove("duraTimeBtn") 
    state.remove("eraseTime")
    state.remove("overTempBtn")
    state.remove("startTimeBtn")
    state.remove("chainError")
    state.remove("dispMonthBtn")
    app.removeSetting("StartTime") 
    app.removeSetting("StartMode") 
    app.removeSetting("StartAfter") 
    app.removeSetting("DuraTime")
    app.removeSetting("monthPercentage")

    if      ( btn == "btnSchEna")           toggleEnaSchBtn()
	else if ( btn == "btnUsda")             getSoilTypeFromUSDA()
    else if ( btn == "addDGBtn")            addDayGroup()
    else if ( btn.startsWith("m")        )  state.dispMonthBtn = btn.minus("m")
    else if ( btn.startsWith("rem")      )  remDayGroup(btn.minus("rem")) 
    else if ( btn.startsWith("n")        )  state.duraTimeBtn = btn.minus("n")
    else if ( btn.startsWith("r")        )  state.dayGrpBtn = btn.minus("r")
    else if ( btn.startsWith("t")        )  state.startTimeBtn = btn.minus("t")
    else if ( btn.startsWith("w")        )  state.dayGroupBtn = btn.minus("w")
    else if ( btn.startsWith("o")        )  state.overTempBtn = btn.minus("o")
    else if ( btn.startsWith("x")        )  state.eraseTime = btn.minus("x")
}

// -----------------------------------------------------------------------------
// Schedule Execution Logic
// -----------------------------------------------------------------------------

def reschedule() {
    unschedule(reschedule)
    schedule('7 7 0 ? * *', reschedule) 
    state.overTempToday = false 
    runIn(15, scheduleNext)
}

def scheduleNext() {
    String myLabel = app.label
    if (app.label.contains('<span ')) { myLabel = app.label.substring(0, app.label.indexOf('<span ')) }
    
    hasZero = state.dayGroup.any { key, value -> value.any { it.value.toString() == "0" } } || state.valves?.isEmpty()
    if (hasZero) {
        logWarn {"Please set Time and Duration"}
        return
    }
    
    unschedule(reschedule)
    unschedule(schedHandler)
    schedule('7 7 0 ? * *', reschedule) 
    logInfo {"Checking $myLabel Schedule."}
    
    Calendar calendar = Calendar.getInstance();
    def cronDay = calendar.get(Calendar.DAY_OF_WEEK);
    def timings = buildTimings(cronDay)
    if (!timings) {
        logWarn {"Nothing scheduled for $myLabel Today."}
        return
    }

    if (!schEnable) { logWarn {"Schedule Paused for $myLabel."}; return }
    if (state.rainHold && rainEnableDevice && rainEnableDevice != "0") { logWarn {"Rain Hold possible for $myLabel Today."} }

    Date date = new Date()
    String akaNow = date.format("HH:mm")
    def hasSched = false
    def sth, stm, sk

    for (timN in timings) {
        sk = timN.key
        if (timN.startTime.startsWith("after_")) continue
        (sth, stm) = timN.startTime.split(':')
        if (akaNow.replace(':', '').toInteger() >= timN.startTime.replace(':', '').toInteger()) continue
        hasSched = true
        break;
    }

    if (hasSched) { 
        schedule("0 ${stm} ${sth} ? * *", schedHandler, [data: ["dKey":"$sk"]]) 
        logInfo {"$myLabel scheduled today."}
        logDebug {"Scheduled events list for today: ${timings}"}
    } else {
        logInfo {"Nothing scheduled for $myLabel today."}
    }
}

def schedHandler(data) {
    unschedule(schedHandler)
    String myLabel = app.label
    if (app.label.contains('<span ')) { myLabel = app.label.substring(0, app.label.indexOf('<span ')) }
    
    logInfo {"Running $myLabel Schedule."}
    String cd = data["dKey"] as String
    def duraT = state.dayGroup."$cd".duraTime

    if(state.dayGroup[cd].ot && !state.overTempToday) {
        logInfo {"No Over Temperature today, skipping."}
        runIn(60, scheduleNext)
        return
    }

    if (duraT == 0) {
        logInfo {"Duration of 0, skipping."}
        runIn(60, scheduleNext)
        return	
    }

    if (rainEnableDevice && rainEnableDevice != "0" && state.rainDeviceData[rainEnableDevice]?.value?.toLowerCase() == "wet") { 
        logWarn {"Rain Hold - schedule skipped for $myLabel Today."}
        runIn(60, scheduleNext)
        return
    }	

    def valve2start = state.valves.findAll { it.value.dayGroup.contains(cd) }.keySet()
    if (!valve2start) {
        logInfo {"No Switch in Day Group."}
        runIn(60, scheduleNext)
        return
    }
    logDebug {"schedHandler: $cd, $state.dayGroup, valve2start: $valve2start"} 

    String dgName = state.dayGroup[cd]?.name ? " (${state.dayGroup[cd].name})" : ""
    logInfo {"Starting execution for Day Group $cd$dgName"}

    String vk = valve2start[0] as String
    if (vk != null) { valve2start = valve2start.tail() }

    def currentValve = settings.valves?.find{it.id == "$vk"}
    currentValve?.on()
    logInfo {"Valve switch ${currentValve?.label ?: currentValve?.name} on."}
    
    state.inCycle = true
    atomicState.cycleStart = now()
    updateMyLabel(3)

    duraT = state.dayGroup."$cd".duraTime
    def currentMonth = new Date().format("M") 	
    def currentMonthPercentage = state.month2month ? state.month2month[currentMonth].toDouble() / 100 : 1  
    def dura = 60 * duraT * currentMonthPercentage
    def duraSeconds = Math.max(dura.toInteger(), 20) 
    
    logDebug {"runIn($duraSeconds, scheduleDurationHandler, [vKey: $vk, dS: $duraSeconds, dV: $valve2start, dKey: $cd])"}
    runIn(duraSeconds, scheduleDurationHandler, [data: [vKey: "$vk", dS: "$duraSeconds", dV: "$valve2start", dKey: "$cd"]]) 
}

def scheduleDurationHandler(data) {
    unschedule(scheduleDurationHandler)
    String vk = data.vKey as String
    String cd = data.dKey as String
    def duraSeconds = data.dS.toInteger()
    def valve2start = data.dV as String
    logDebug {"schedDurHandler: valveStop: $vk, in Duration: $duraSeconds, next: $valve2start"}

    def currentValve = settings.valves?.find{it.id == "$vk"}
    currentValve?.off()
    logInfo {"Valve switch ${currentValve?.label ?: currentValve?.name} off."}

    pauseExecution(20000)
    
    if (state.rainHold && rainEnableDevice && rainEnableDevice != "0") {
        logWarn {"Rain Hold triggered mid-cycle. Aborting remaining zones."}
        state.inCycle = false
        atomicState.cycleEnd = now()
        runIn(30, scheduleNext)
        updateMyLabel(4)
        return
    }

    if (valve2start != '[]') {
        valve2start = valve2start.replaceAll(/\[|\]/, '').split(',').collect { it.trim().toInteger() }
        String nextVk = valve2start[0] as String
        if (nextVk != null) {
            valve2start = valve2start.tail()
            currentValve = settings.valves?.find{it.id == "$nextVk"}
            currentValve?.on()
            logInfo {"Valve switch ${currentValve?.label ?: currentValve?.name} on."}
            runIn(duraSeconds, scheduleDurationHandler, [data: [vKey: "$nextVk", dS: "$duraSeconds", dV: "$valve2start", dKey: "$cd"]])
        }
    } else {
        Calendar calendar = Calendar.getInstance();
        def cronDay = calendar.get(Calendar.DAY_OF_WEEK);
        Map aWeek = [1:'7', 2:'1', 3:'2', 4:'3', 5:'4', 6:'5', 7:'6']
        def todayIndex = aWeek[cronDay]

        def cascadedGroup = state.dayGroup.find { k, v ->
            v[todayIndex] == true && v.startTime == "after_${cd}"
        }

        if (cascadedGroup) {
            logInfo {"Day Group $cd complete. Cascading to dependent Day Group ${cascadedGroup.key}."}
            runIn(20, schedHandler, [data: ["dKey": cascadedGroup.key]])
        } else {
            state.inCycle = false
            atomicState.cycleEnd = now()
            runIn(30, scheduleNext)
            updateMyLabel(4)
        }
    }
}

def buildTimings(cronDayOf) {
    Map aWeek = [1:'7', 2:'1', 3:'2', 4:'3', 5:'4', 6:'5', 7:'6']
    def result = state.dayGroup.findAll { key, value -> value[aWeek[cronDayOf]] == true }.keySet()
    return result.collect { key -> [key: key, duraTime: state.dayGroup[key]?.duraTime, startTime: state.dayGroup[key]?.startTime]}.findAll { it.startTime != null }.sort { it.startTime.startsWith("after_") ? "99:99" : it.startTime } 
}

// -----------------------------------------------------------------------------
// Sensor Callbacks & API Methods
// -----------------------------------------------------------------------------

def recvOutdoorTempHandler(evt) {
    def currentTempStr = evt?.value?.toString()
    def maxTempStr = settings.maxOutdoorTemp?.toString()
    
    // Log every hardware event regardless of latch state for observability
    logDebug {"OutdoorTemp Event Received - Ambient: ${currentTempStr}°, Max Limit: ${maxTempStr}°. Current Latch State: ${state.overTempToday}"}

    if (!state.overTempToday) { 
        if (currentTempStr?.isNumber() && maxTempStr?.isNumber()) {
            state.overTempToday = new BigDecimal(currentTempStr) > new BigDecimal(maxTempStr) 
            
            // Alert the user when the state machine permanently flips for the day
            if (state.overTempToday) {
                logInfo {"Maximum temperature threshold crossed. OverTemp schedules are unlocked for the remainder of the day."}
            }
        } else {
            logWarn {"OutdoorTemp evaluation bypassed: Malformed or null telemetry. Current: ${currentTempStr}, Max: ${maxTempStr}"}
        }
    }
}

def recvOutdoorRainHandler(evt) {
    def targetDevice = settings.rainEnableDevice?.toString()
    def evtDeviceId = evt?.deviceId?.toString()
    
    if (targetDevice && targetDevice != "0" && targetDevice == evtDeviceId) {
        state.rainDeviceData[evtDeviceId] = [ value: evt?.value, name : evt?.displayName ]
        state.rainHold = evt?.value?.toString()?.toLowerCase() == "wet"
        logDebug {"OutdoorRain update from Device. rainHold: $state.rainHold"}
    }
}

def getSoilTypeFromUSDA() {
    state.geo = state.geo ?: [:]
    if(state.geo.lat == null || state.geo.lon == null) {
        state.geo.lat = location?.latitude
        state.geo.lon = location?.longitude
        logInfo {"Using lat/lon from hub location"}
    }
    def lat = state.geo?.lat
    def lon = state.geo?.lon
    if(!lat || !lon) {
        state.usdaSoilMsg = "Hub coordinates unavailable."
        logWarn {"${state.usdaSoilMsg}"}
        return
    }
    def sda = getSoilData(lat, lon)
    if(!sda || !sda.textureMapped) {
        state.usdaSoilMsg = "No USDA data returned for (${lat},${lon})"
        logWarn {"${state.usdaSoilMsg}"}
        return
    }
    def mapped = sda.textureMapped
    def raw = sda.textureRaw ?: "n/a"
    def hyd = sda.hydgrp ?: "n/a"
    state.defaultSoilType = mapped
    state.defaultHydgrp = hyd
    state.usdaSoilMsg = "USDA soil detected: ${mapped} (USDA: ${raw}, Group ${hyd})"
    logInfo {"Soil: ${state.usdaSoilMsg}"}
}

def getSoilData(lat, lon) {
    def d = 0.0001
    def poly = "POLYGON((${lon-d} ${lat-d},${lon+d} ${lat-d},${lon+d} ${lat+d},${lon-d} ${lat+d},${lon-d} ${lat-d}))"
    def query = """SELECT TOP 1 mapunit.musym, mapunit.muname, component.compname, component.hydgrp, chtexturegrp.texdesc FROM mapunit INNER JOIN component ON component.mukey = mapunit.mukey INNER JOIN chorizon ON chorizon.cokey = component.cokey INNER JOIN chtexturegrp ON chtexturegrp.chkey = chorizon.chkey WHERE mapunit.mukey IN ( SELECT mukey FROM SDA_Get_Mukey_from_intersection_with_WktWgs84('${poly}') )"""
    def params = [uri: "https://sdmdataaccess.nrcs.usda.gov/tabular/post.rest", contentType: "application/x-www-form-urlencoded", body: [query: query], timeout: 20]

    try {
        def respText = ''
        httpPost(params) { r ->
            def t = r?.data
            def tn = t?.class?.name ?: ''
            respText = (tn.contains('InputStream') || tn.contains('Reader')) ? t?.getText('UTF-8') : t?.toString()
        }
        if (!respText) { logWarn {"getSoilData(): empty SDA response for (${lat},${lon})"}; return null }
        
        respText = respText.substring(respText.indexOf("<"))
        def m = respText =~ /(?s)<Table>(.*?)<\/Table>/
        if (!m.find()) { logWarn {"getSoilData(): no <Table> block in SDA response"}; return null }
        
        def tableXML = "<Table>${m.group(1)}</Table>"
        tableXML = tableXML.replaceAll(/&(?![a-zA-Z]+;|#\d+;)/, '&amp;')
        def xml = new XmlSlurper(false, false).parseText(tableXML)
        
        def musym = xml?.musym?.text() ?: ''
        def muname = xml?.muname?.text() ?: ''
        def compname = xml?.compname?.text() ?: ''
        def hydgrp = xml?.hydgrp?.text() ?: ''
        def texdesc = xml?.texdesc?.text() ?: ''
        if (!texdesc && !muname){ logWarn {"getSoilData(): no soil data for (${lat},${lon})"}; return null }
        return [musym: musym, muname: muname, compname: compname, hydgrp: hydgrp, textureRaw: texdesc, textureMapped: mapSoilTextureToClass(texdesc)]
    } catch(e) { logWarn {"getSoilData() SDA error: ${e.message}"}; return null }
}

def mapSoilTextureToClass(String texture) {
    if(!texture) return "Loam"
    def t = texture.toLowerCase().trim()
    switch(true) {
        case {t == "sand" || t.contains("coarse sand") || t.contains("fine sand")}: return "Sand"
        case {t.contains("loamy sand") || t.contains("sandy loam") || t.contains("fine sandy loam")}: return "Loamy Sand"
        case {t.contains("loam") || t.contains("silt loam") || t.contains("silty clay loam") || t.contains("sandy clay loam")}: return "Loam"
        case {t.contains("clay loam") || t.contains("silty clay") || t.contains("sandy clay")}: return "Clay Loam"
        case {t == "clay" || t.contains("heavy clay") || t.contains("vertisol")}: return "Clay"
        default: return "Loam"
    }
}

// -----------------------------------------------------------------------------
// UI Utilities
// -----------------------------------------------------------------------------

void updateMyLabel(num) {
    String baseLabel = app.label?.with { contains('<span ') ? substring(0, indexOf('<span ')) : it } ?: ""
    String status = ""
    if (settings.schEnable != true) { status = '<span style="color:Crimson"> (inactive)</span>' } 
    else if (atomicState.isPaused) { status = '<span style="color:Crimson"> (paused)</span>' } 
    else if (state.inCycle) {
        def beganAt = atomicState.cycleStart ? "started ${fixDateTimeString(atomicState.cycleStart)}" : "running"
        status = "<span style=\"color:Green\"> (${beganAt})</span>"
    } else if (state.inCycle != null && !state.inCycle) {
        def endedAt = atomicState.cycleEnd ? "finished ${fixDateTimeString(atomicState.cycleEnd)}" : "idle"
        status = "<span style=\"color:Green\"> (${endedAt})</span>"
    }
    String newLabel = baseLabel + status
    if (app.label != newLabel) { app.updateLabel(newLabel) }
}

String fixDateTimeString(eventDate) {
    def target = new Date(eventDate)
    def today = new Date().clearTime()
    def yesterday = new Date(today.time - 1 * 24 * 60 * 60 * 1000) 
    def tomorrow = new Date(today.time + 1 * 24 * 60 * 60 * 1000) 

    String myDate = ''
    boolean showTime = true

    if (target.clearTime() == today) { myDate = 'today' } 
    else if (target.clearTime() == yesterday) { myDate = 'yesterday' } 
    else if (target.clearTime() == tomorrow) { myDate = 'tomorrow' } 
    else if (target.format('yyyy-MM-dd') == '2035-01-01') { myDate = 'a long time from now'; showTime = false } 
    else { myDate = "on ${target.format('MM-dd')}" }

    target = new Date(eventDate)
    String myTime = showTime ? target.format('h:mma').toLowerCase() : ''
    return myTime ? "${myDate} at ${myTime}" : myDate
}

void logDebug(Closure msg) {
    if (settings.debugEnable) { log.debug "${msg()}" }
}

def logWarn(Closure msg) { 
    log.warn "${msg()}"
}

def logInfo(Closure msg) {
    if (settings.infoEnable) { log.info "${msg()}" }
}

def logsOff() {
    logWarn {"debug logging Disabled..."}
    app?.updateSetting("debugEnable",[value:"false",type:"bool"])
}

def installCheck() {         
    state.appInstalled = app.getInstallationState() 
    if(state.appInstalled != 'COMPLETE'){
        state.paused = false
        app?.updateSetting("schEnable",[value:"true",type:"bool"])
        section{paragraph "Please hit 'Done' to Complete the install."}
    } else {
        logDebug {"$app.name is Installed Correctly"}
    }
}

String buttonLink(String btnName, String linkText, color = "#1A77C9", font = "15px") {
    "<div class='form-group'><input type='hidden' name='${btnName}.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' style='color:$color;cursor:pointer;font-size:$font'>$linkText</div></div><input type='hidden' name='settings[$btnName]' value=''>"
}

def sectFormat(type, myText=""){ 
    if(type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
    if(type == "subTitle") return "<p style='color:#1A77C9;font-weight: bold; font-size: 1.4em;'>${myText}</p>"
}

def displayHeader() {
    section (sectFormat("title", "SprinklerSchedulePlus")) {
        paragraph "<div style='color:#1A77C9;text-align:right;font-weight:small;font-size:9px;'>Current Version: ${version()} - &copy; 2023 C Steele</div>"
        paragraph "\n<hr style='background-color:#1A77C9; height: 1px; border: 0;'></hr>"
    }
}

private Integer timeToSeconds(String timeStr) {
    if (!timeStr || timeStr.startsWith("after_")) return null
    def parts = timeStr.split(':')
    int h = parts[0].toInteger()
    int m = parts[1].toInteger()
    return (h * 3600) + (m * 60)
}

String menuHeader(titleText){"<div style=\"width:102%;background-color:#696969;color:white;padding:4px;font-weight: bold;box-shadow: 1px 2px 2px #bababa;margin-left: -10px\">${titleText}</div>"}