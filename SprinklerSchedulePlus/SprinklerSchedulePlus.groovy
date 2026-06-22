/* =============================================================================
Hubitat Elevation Application
SprinklerSchedulePlus (Monolithic)

    Inspiration: Lighting Schedules https://github.com/matt-hammond-001/hubitat-code
    Inspiration: github example from Hubitat of lightsUsage.groovy
    This fork: Sprinkler Schedules https://github.com/relifram/Hubitat-public/tree/master/SprinklerSchedule

-----------------------------------------------------------------------------
This code is licensed as follows:

	Portions:
	 	Copyright (c) 2022 Hubitat, Inc.  All Rights Reserved Bruce Ravenel 

	BSD 3-Clause License
	
	Copyright (c) 2026, J Haubold
	Copyright (c) 2023, C Steele
	Copyright (c) 2020, Matt Hammond
	All rights reserved.
-----------------------------------------------------------------------------
 * Version 2.1.0: added evapiortranspiration calculation module.  --in work.
 * Version 2.0.3: bug fixes
 * Version 2.0.2: Pagination for the main page, removed support for valves
 * Version 2.0.1: Monolithic architecture consolidation. Semantic variable standardization.
 */

public static String version() { return "v2.0.2" }

definition(
    name: "SprinklerSchedulePlus",
    namespace: "relifram",
    author: "J Haubold",
    description: "Monolithic controller for switch relays to a timing schedule",
    importUrl: "https://raw.githubusercontent.com/relifram/Hubitat-public/master/SprinklerSchedule/SprinklerSchedulePlus.groovy",
    documentationLink: "",
    singleInstance: true,
    iconUrl: "",
    iconX2Url: ""
)

preferences {
    page(name: "mainPage")
    page(name: "schedulePage")
    page(name: "environmentPage")
	page(name: "etConfigPage")
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
            
            input "etEnable", "bool", title: "<b>Enable Smart Evapotranspiration (ET)?</b>", required: false, defaultValue: false, submitOnChange: true

            paragraph "\n<b>Switch Select</b>"
            input "valves",
                "capability.switch",
                title: "Control which valve switches?",
                multiple: true,
                required: false,
                submitOnChange: true
        }

        if (schEnable && valves) {
            section(menuHeader("Configuration")) {
                href "schedulePage", title: "Timetable Matrix", description: "Configure Day Groups, durations, and map switches.", state: "complete"
                href "environmentPage", title: "Global Settings & Overrides", description: "Configure seasonal adjustments, rain holds, and temperature limits.", state: "complete"
				if (settings.etEnable) {
                    href "etConfigPage", title: "Evapotranspiration (ET) Config", description: "Map local weather telemetry and crop coefficients.", state: "complete"
                }            }

            section(menuHeader("System Status")) {
                def currentMonth = new Date().format("M") 	
                def seasonalMultiplier = state.month2month ? state.month2month[currentMonth].toDouble() : 1  
                 
                String statusHtml = "<div style='background-color: rgba(73, 163, 125, 0.3);'>"	
                
                if (settings.etEnable) {
                    statusHtml += "<b>💧 Smart ET Scheduling</b> is <b>ACTIVE</b><br>" +
                    "<b>Rain hold</b> is $state.rainHold<br>" +
                    "<b>Soil</b> is $state.defaultSoilType<br>"
                } else if (state.month2month) {
                    statusHtml += "<b>Adjust valve timing</b> by Month is active. Current month is: <b>$seasonalMultiplier%</b><br>" +
                    "<b>Rain hold</b> is $state.rainHold<br>" +
                    "<b>Soil</b> is $state.defaultSoilType<br>"
                }
                
                if (settings.outdoorTempDevice) {
                    def currentTemp = settings.outdoorTempDevice.currentValue("temperature") ?: "N/A"
                    def todayHigh = state.todayHighTemp ?: currentTemp
                    def triggerLimit = settings.maxOutdoorTemp ?: "N/A"
                    statusHtml += "<b>Temperature:</b> Current: ${currentTemp}° | Today's High: ${todayHigh}° | Trigger Limit: ${triggerLimit}°<br>"
                }
                
                if (state.overTempToday) { statusHtml += "Sometime today, the outside temperature <b>exceeded</b> the limit you set of $settings.maxOutdoorTemp and any Over Temp schedules <b>will run.</b><br>" }
                statusHtml += valves?.collect { device -> "<b>${device.label ?: device.name}</b> is ${device.currentValue('switch') == 'on' ? 'On' : 'Off'}"}?.join(', ') ?: ""
                statusHtml += "</div>"
                paragraph statusHtml
           
				section(menuHeader("System Logging")) {
				input "infoEnable", "bool", title: "Enable activity logging", required: false, defaultValue: true, width: 2
				input "debugEnable", "bool", title: "Enable debug logging", required: false, defaultValue: false, submitOnChange: true, width: 3

					if (debugEnable) {
						input "debugTimeout", "enum", required: false, defaultValue: "0", title: "Automatic debug Log Disable Timeout?", width: 3,  \
								options: [ "0":"None", "1800":"30 Minutes", "3600":"60 Minutes", "86400":"1 Day" ]
					}
				}
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

            String switchHeaderText = settings.etEnable ? "<b>Select Switches into Day Groups & Hydraulics</b>" : "<b>Select Switches into Day Groups</b>"
            paragraph switchHeaderText
            paragraph getKcHelpHtml()
            
            paragraph displayGrpSched()		
            selectDayGroup()
            // Render individual inputs below the matrix
            displayZoneKc()
            displayZoneAppRate()
			
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
    }
}

def etConfigPage() {
    dynamicPage(name: "etConfigPage", title: "Evapotranspiration (ET) Telemetry", uninstall: false, install: false) {
        displayHeader()
        
        section(menuHeader("Agronomy Baseline")) {
            String helpHtml = "<p><b>Global Crop Coefficient (Kc)</b><br>" +
                "<i>Defines the transpiration profile of your primary vegetation. This multiplier scales the atmospheric evaporation rate to match your yard's specific biological needs. For example, Kentucky Bluegrass (KBG) maintained at a taller 3-inch height has more leaf surface area and transpires more water (higher Kc) than KBG cut to 1.5 inches.</i></p>" +
                
                "<div style='padding-bottom: 12px;'><table style='border-collapse: collapse; width: 100%; font-size: 13px; border: 1px solid #ccc; background-color: #f9f9f9;'>" +
                "<tr style='background-color: #e0e0e0; border-bottom: 2px solid #ccc;'><th style='padding: 6px; text-align: left;'>Vegetation Type</th><th style='padding: 6px; text-align: left;'>Typical Kc</th></tr>" +
                "<tr><td style='padding: 6px; border-bottom: 1px solid #ccc;'>Cool-Season Turf (KBG, Fescue, Rye) - Tall (3\"+)</td><td style='padding: 6px; border-bottom: 1px solid #ccc;'>0.85</td></tr>" +
                "<tr><td style='padding: 6px; border-bottom: 1px solid #ccc;'>Cool-Season Turf (KBG, Fescue, Rye) - Short (1.5\")</td><td style='padding: 6px; border-bottom: 1px solid #ccc;'>0.75</td></tr>" +
                "<tr><td style='padding: 6px; border-bottom: 1px solid #ccc;'>Warm-Season Turf (Bermuda, Zoysia, St. Augustine)</td><td style='padding: 6px; border-bottom: 1px solid #ccc;'>0.60 - 0.65</td></tr>" +
                "<tr><td style='padding: 6px; border-bottom: 1px solid #ccc;'>Mixed Vegetable Garden</td><td style='padding: 6px; border-bottom: 1px solid #ccc;'>1.00 - 1.15</td></tr>" +
                "<tr><td style='padding: 6px;'>Established Trees & Native Shrubs</td><td style='padding: 6px;'>0.50 - 0.60</td></tr>" +
                "</table></div>" +
                
                "<div style='font-size: 12px; padding-bottom: 5px;'><b>Resources:</b> <a href='https://www.fao.org/3/X0490E/x0490e0b.htm' target='_blank'>FAO Irrigation Standard (Chapter 6)</a> | It is highly recommended to search your local University Agricultural Extension for region-specific data.</div>"

            paragraph helpHtml
            input "globalCropCoefficient", "decimal", title: "Set Crop Coefficient (Kc):", defaultValue: 0.8, range: "0.1..1.5", required: true, width: 6
        }

        section(menuHeader("Atmospheric Sensors")) {
            String sensorHelp = "<p><i>Map individual devices for your weather telemetry. You may select the same multi-sensor device for multiple fields.</i></p>" +
                "<div style='font-size: 13px; border-left: 3px solid #1A77C9; padding-left: 10px; margin-bottom: 15px;'>" +
                "<b>Data Mapping Advice:</b><br>" +
                "• <b>Wind Speed:</b> Select an averaged attribute (e.g., 10-minute average) rather than instantaneous gusts to prevent erratic evaporation spikes.<br>" +
                "• <b>Rainfall:</b> Select a daily accumulation attribute (midnight-to-midnight) rather than a rain rate or sliding 24-hour total to ensure accurate daily bucket tracking." +
                "</div>"
            
            paragraph sensorHelp
            
            // Temperature
            input "etTempDevice", "capability.temperatureMeasurement", title: "Air Temperature Sensor", multiple: false, required: false, submitOnChange: true, width: 6
            if (settings.etTempDevice) {
                def tempAttrs = settings.etTempDevice.supportedAttributes.collect { it?.toString() }.toSet().sort()
                input "attrTemperature", "enum", title: "Temperature Attribute", options: tempAttrs, defaultValue: "temperature", required: false, width: 6
            }

            // Humidity
            input "etHumidDevice", "capability.relativeHumidityMeasurement", title: "Relative Humidity Sensor", multiple: false, required: false, submitOnChange: true, width: 6
            if (settings.etHumidDevice) {
                def humidAttrs = settings.etHumidDevice.supportedAttributes.collect { it?.toString() }.toSet().sort()
                input "attrHumidity", "enum", title: "Humidity Attribute", options: humidAttrs, defaultValue: "humidity", required: false, width: 6
            }

            // Solar Radiation
            input "etSolarDevice", "capability.illuminanceMeasurement", title: "Solar Radiation Sensor", multiple: false, required: false, submitOnChange: true, width: 6
            if (settings.etSolarDevice) {
                def solarAttrs = settings.etSolarDevice.supportedAttributes.collect { it?.toString() }.toSet().sort()
                input "attrSolar", "enum", title: "Solar Attribute (e.g., illuminance/solarradiation)", options: solarAttrs, defaultValue: "illuminance", required: false, width: 6
            }

            // Wind Speed
            input "etWindDevice", "capability.sensor", title: "Wind Speed Sensor", multiple: false, required: false, submitOnChange: true, width: 6
            if (settings.etWindDevice) {
                def windAttrs = settings.etWindDevice.supportedAttributes.collect { it?.toString() }.toSet().sort()
                input "attrWind", "enum", title: "Wind Speed Attribute", options: windAttrs, required: false, width: 6
            }
            
            // Rainfall
            input "etRainGauge", "capability.sensor", title: "Rain Gauge", multiple: false, required: false, submitOnChange: true, width: 6
            if (settings.etRainGauge) {
                def rainAttrs = settings.etRainGauge.supportedAttributes.collect { it?.toString() }.toSet().sort()
                input "attrRainAccumulation", "enum", title: "Daily Rain Attribute (e.g., dailyrainin)", options: rainAttrs, required: false, width: 6
            }
        
            // Soil Temperature
            input "etSoilProbe", "capability.temperatureMeasurement", title: "Soil Temperature Gauge", multiple: false, required: false, submitOnChange: true, width: 6
            if (settings.etSoilProbe) {
                def soilAttrs = settings.etSoilProbe.supportedAttributes.collect { it?.toString() }.toSet().sort()
                input "attrSoilTemp", "enum", title: "Soil Temperature Attribute", options: soilAttrs, defaultValue: "temperature", required: false, width: 6
            }
        }
    }
}
// -----------------------------------------------------------------------------
// UI Rendering & Matrix Management
// -----------------------------------------------------------------------------

String getKcHelpHtml() {
    if (!settings.etEnable) return ""
    return "<details style='margin-bottom: 15px; cursor: pointer;'><summary><b><span style='color: #1A77C9;'>📚 Show Crop Coefficient (Kc) Reference Guide</span></b></summary>" +
           "<div style='padding: 10px 0px;'><table style='border-collapse: collapse; width: 100%; font-size: 13px; border: 1px solid #ccc; background-color: #f9f9f9;'>" +
           "<tr style='background-color: #e0e0e0; border-bottom: 2px solid #ccc;'><th style='padding: 6px; text-align: left;'>Vegetation Type</th><th style='padding: 6px; text-align: left;'>Typical Kc</th></tr>" +
           "<tr><td style='padding: 6px; border-bottom: 1px solid #ccc;'>Cool-Season Turf (KBG, Fescue, Rye) - Tall (3\"+)</td><td style='padding: 6px; border-bottom: 1px solid #ccc;'>0.85</td></tr>" +
           "<tr><td style='padding: 6px; border-bottom: 1px solid #ccc;'>Cool-Season Turf (KBG, Fescue, Rye) - Short (1.5\")</td><td style='padding: 6px; border-bottom: 1px solid #ccc;'>0.75</td></tr>" +
           "<tr><td style='padding: 6px; border-bottom: 1px solid #ccc;'>Warm-Season Turf (Bermuda, Zoysia, St. Augustine)</td><td style='padding: 6px; border-bottom: 1px solid #ccc;'>0.60 - 0.65</td></tr>" +
           "<tr><td style='padding: 6px; border-bottom: 1px solid #ccc;'>Mixed Vegetable Garden</td><td style='padding: 6px; border-bottom: 1px solid #ccc;'>1.00 - 1.15</td></tr>" +
           "<tr><td style='padding: 6px;'>Established Trees & Native Shrubs</td><td style='padding: 6px;'>0.50 - 0.60</td></tr>" +
           "</table></div></details>"
}

String displayDayGroups() {
    if (state.duraTimeBtn && settings.DuraTime != null) {
        def targetIndex = state.duraTimeBtn.toString()
        if (state.dayGroup.containsKey(targetIndex)) { state.dayGroup[targetIndex].duraTime = settings.DuraTime }
        state.remove("duraTimeBtn")
        app.removeSetting("DuraTime")
    }

    if (state.startTimeBtn) {
        def inputMode = settings.StartMode ?: "time"
        if ((inputMode == "time" && settings.StartTime) || (inputMode == "after" && settings.StartAfter)) {
            def targetIndex = state.startTimeBtn.toString()
            def targetValue = inputMode == "time" ? Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSX", settings.StartTime).format('HH:mm') : settings.StartAfter
            
            boolean configurationConflict = false
            if (inputMode == "after") {
                def collisionMatch = state.dayGroup.find { key, data -> data.startTime == targetValue && key.toString() != targetIndex }
                if (collisionMatch) {
                    configurationConflict = true
                    state.chainError = "<b>Error:</b> Day Group ${collisionMatch.key} is already configured to follow Group ${targetValue.replace('after_', '')}. Multiple groups cannot follow a single group. Please select a different trigger."
                }
            }

            if (!configurationConflict) {
                state.remove("chainError")
                if (state.dayGroup.containsKey(targetIndex)) { state.dayGroup[targetIndex].startTime = targetValue }
                state.remove("startTimeBtn")
                app.removeSetting("StartTime")
                app.removeSetting("StartMode")
                app.removeSetting("StartAfter")
            }
        }
    }

    if (state.dayGroupBtn) {
        def buttonString = state.dayGroupBtn.toString()
        def stringLength = buttonString.length()
        def targetGroupId = buttonString.substring(0, stringLength - 1)
        def targetDayIndex = buttonString.substring(stringLength - 1)
        if (state.dayGroup.containsKey(targetGroupId)) { state.dayGroup[targetGroupId][targetDayIndex] = !state.dayGroup[targetGroupId][targetDayIndex] }
        state.remove("dayGroupBtn")
        logDebug {"displayDayGroups Item: $targetGroupId.$targetDayIndex"}
    }
    
    if (state.overTempBtn) {
        def targetGroupId = state.overTempBtn.toString()
        if (state.dayGroup.containsKey(targetGroupId)) { state.dayGroup[targetGroupId].ot = !state.dayGroup[targetGroupId].ot }
        state.remove("overTempBtn")
    }

    if (state.eraseTime) {
        def targetIndex = state.eraseTime.toString()
        if (state.dayGroup.containsKey(targetIndex)) { 
            state.dayGroup[targetIndex].startTime = null
            state.dayGroup[targetIndex].duraTime = null
        }
        state.remove("eraseTime")
        app.removeSetting("eraseTime")
        paragraph "<script>{changeSubmit(this)}</script>"
    }

    String tableHtml = "<script src='https://code.iconify.design/iconify-icon/1.0.0/iconify-icon.min.js'></script>"
    tableHtml += "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .tstat-col td,.tstat-col th { padding:8px 8px;text-align:center;font-size:12px} .tstat-col td {font-size:15px }" +
        "</style><div style='overflow-x:auto'><table class='mdl-data-table tstat-col' style=';border:2px solid black'>"
        
    String durationHeader = settings.etEnable ? "Max Duration" : "Duration"
    tableHtml += "<thead><tr style='border-bottom:2px solid black'>" +
        "<th style='border-right:2px solid black'>Day Group</th>" +
        "<th>Mon</th><th>Tue</th><th>Wed</th><th>Thu</th><th>Fri</th><th>Sat</th><th>Sun</th>" +
        "<th style='color:red;'>Delete</th>" +
        "<th style='color:#db7321;'>OverTemp</th>" +
        "<th>Start Time</th><th>$durationHeader</th><th>Reset</th>" +
        "</tr></thead><tr style='color:black'border = 1>" 

    String iconChecked = "<i class='he-checkbox-checked'></i>"
    String iconUnchecked = "<i class='he-checkbox-unchecked'></i>"
    String iconPlus = "<i class='ic--sharp-plus'>+</i>"
    String buttonAddGroup = buttonLink("addDGBtn", iconPlus, "#1A77C9", "")

    String htmlRows = ""
    state.dayGroup.each { groupId, groupData -> 
        tableHtml += htmlRows
        tableHtml += "<th>$groupId</th>"
        for (int dayIndex = 1; dayIndex < 8; dayIndex++) { 
            String buttonDayOff = buttonLink("w${groupId}${dayIndex}", iconUnchecked, "#1A77C9", "")
            String buttonDayOn = buttonLink("w${groupId}${dayIndex}", iconChecked, "#1A77C9", "")
            tableHtml += (groupData."$dayIndex") ? "<th>$buttonDayOn</th>" : "<th>$buttonDayOff</th>" 
        }
        
        String buttonRemoveGroup = buttonLink("rem$groupId", "<iconify-icon icon='mdi:trash-can-outline'></iconify-icon>", "red", "22px")
        tableHtml += "<th>$buttonRemoveGroup</th>"
        
        String buttonTempOff = buttonLink("o$groupId", iconUnchecked, "#db7321", "")
        String buttonTempOn = buttonLink("o$groupId", iconChecked, "#db7321", "")
        tableHtml += (groupData."ot") ? "<th>$buttonTempOn</th>" : "<th>$buttonTempOff</th>" 
        
        String rawStartTime = state.dayGroup[groupId]?.startTime
        String startTimeDisplay = rawStartTime ? (rawStartTime.startsWith("after_") ? "After Grp ${rawStartTime.split('_')[1]}" : rawStartTime) : "Set Time"
        String buttonStartTime = rawStartTime ? buttonLink("t$groupId", startTimeDisplay, "black") : buttonLink("t$groupId", "Set Time", "green")
        
        String savedDuration = state.dayGroup[groupId]?.duraTime 
        String buttonDuration = savedDuration ? buttonLink("n$groupId", savedDuration, "purple") : buttonLink("n$groupId", "Select", "green")
        
        String buttonReset = buttonLink("x$groupId", "<iconify-icon icon='bx:reset'></iconify-icon>", "black", "20px")
        tableHtml += "<th>$buttonStartTime</th><th>$buttonDuration</th><th title='Reset $groupId' style='padding:0px 0px'>$buttonReset</th>"
        htmlRows = "</tr><tr>" 
    }
    tableHtml += "</tr><tr>"
    tableHtml += "<th>$buttonAddGroup</th><th colspan=4> <- Add new Day Group</th><th colspan=8>&nbsp;</th>"
    tableHtml += "</tr></table></div>"
    return tableHtml
}

def displayDuration() {
    if(state.duraTimeBtn) {
        def targetIndex = state.duraTimeBtn.toString()
        def savedDuration = state.dayGroup[targetIndex]?.duraTime
        String inputTitle = settings.etEnable ? "Maximum Duration limit (minutes)" : "Sprinkler Duration (minutes)"
        input "DuraTime", "decimal", title: inputTitle, submitOnChange: true, width: 4, range: "0..300", defaultValue: savedDuration, newLineAfter: true
    }
}
def displayStartTime() {
    if(state.startTimeBtn) {
        def targetIndex = state.startTimeBtn.toString()
        def currentStartTime = state.dayGroup[targetIndex]?.startTime
        def isChainedGroup = currentStartTime?.toString()?.startsWith("after_")
        def effectiveRenderMode = settings.StartMode ?: (isChainedGroup ? "after" : "time")

        input "StartMode", "enum", title: "Start Trigger", submitOnChange: true, width: 3, options: ["time":"Scheduled Time", "after":"After Day Group"], defaultValue: isChainedGroup ? "after" : "time"
        
        if (effectiveRenderMode == "after") {
            def availableGroups = [:]
            state.dayGroup.each { key, data -> if (key.toString() != targetIndex) availableGroups["after_${key}"] = "Day Group ${key}" }
            input "StartAfter", "enum", title: "Select Group", submitOnChange: true, width: 3, options: availableGroups, defaultValue: isChainedGroup ? currentStartTime : null, newLineAfter: false
            
            if (state.chainError) {
                paragraph "<div style='color:red; padding-top: 4px'>${state.chainError}</div>"
            }
        } else {
            input "StartTime", "time", title: "At This Time", submitOnChange: true, width: 3, defaultValue: isChainedGroup ? null : currentStartTime, newLineAfter: false
        }
        input "DoneTime$targetIndex", "button", title: "  Done with time  ", width: 2, newLineAfter: true
    }
}



String displayGrpSched() {
    String tableHtml = "<script src='https://code.iconify.design/iconify-icon/1.0.0/iconify-icon.min.js'></script>"
    tableHtml += "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .tstat-col td,.tstat-col th { padding:8px 8px;text-align:center;font-size:12px} .tstat-col td {font-size:15px }" +
        "</style><div style='overflow-x:auto'><table class='mdl-data-table tstat-col' style=';border:2px solid black'>"
        
    String etHeaders = settings.etEnable ? "<th>ET Mode</th><th>Crop Coeff (Kc)</th><th>App Rate (in/hr)</th>" : ""
    tableHtml += "<thead><tr style='border-bottom:2px solid black'>" +
        "<th style='border-right:2px solid black'>Valve</th>" +
        "<th>Day Group</th>" +
        etHeaders +
        "</tr></thead>"

    state.valves.keySet().findAll { deviceId -> !(deviceId in valves.id) }.each { orphanedId -> state.valves.remove(orphanedId) }
    valves?.sort{it.displayName.toLowerCase()}.each { hardwareDevice ->
        String deviceLinkHtml = "<a href='/device/edit/$hardwareDevice.id' target='_blank' title='Open Device Page for $hardwareDevice'>$hardwareDevice"
        String assignedGroups = state.valves[hardwareDevice.id].dayGroup.join(', ')
        String buttonGroupSelection = assignedGroups ? buttonLink("r$hardwareDevice.id", assignedGroups, "purple") : buttonLink("r$hardwareDevice.id", "Select", "green")
        
        String etCells = ""
        if (settings.etEnable) {
            def isEtActive = state.valves[hardwareDevice.id].containsKey('etMode') ? state.valves[hardwareDevice.id].etMode : true
            String buttonEtMode = isEtActive ? buttonLink("e$hardwareDevice.id", "<iconify-icon icon='mdi:water-check'></iconify-icon> Smart", "green") : buttonLink("e$hardwareDevice.id", "<iconify-icon icon='mdi:water-minus'></iconify-icon> Static", "black")
            
            String buttonKc = "<i>Static</i>"
            String buttonAppRate = "<i>Static</i>"
            
            if (isEtActive) {
                def fallbackKc = settings.globalCropCoefficient != null ? settings.globalCropCoefficient : 0.8
                def savedKc = state.valves[hardwareDevice.id]?.kc ?: fallbackKc
                def savedAppRate = state.valves[hardwareDevice.id]?.appRate ?: "Set Rate"
                
                buttonKc = buttonLink("k$hardwareDevice.id", savedKc.toString(), "purple")
                buttonAppRate = savedAppRate == "Set Rate" ? buttonLink("a$hardwareDevice.id", savedAppRate, "green") : buttonLink("a$hardwareDevice.id", savedAppRate.toString(), "purple")
            }
            
            etCells = "<td title='Toggle ET Calculation'>$buttonEtMode</td><td title='Set Kc for ${hardwareDevice.displayName}'>$buttonKc</td><td title='Set App Rate for ${hardwareDevice.displayName}'>$buttonAppRate</td>"
        }
        
        tableHtml += "<tr style='color:black'><td style='border-right:2px solid black'>$deviceLinkHtml</td>" +
            "<td title='${assignedGroups ? "Deselect $assignedGroups" : "Select Day Group"}'>$buttonGroupSelection</td>$etCells</tr>"
    }  
    tableHtml += "</table></div>"
    return tableHtml
}

def displayZoneKc() {
    // 1. Intercept the page reload from hitting 'return'
    if (state.kcBtn && settings.ZoneKc != null && settings.ZoneKc.toString() != "") {
        def targetValveId = state.kcBtn.toString()
        def clonedValves = state.valves.collectEntries { k, v -> [k, v.clone()] }
        clonedValves[targetValveId].kc = settings.ZoneKc
        state.valves = clonedValves
        
        state.remove("kcBtn")
        app.removeSetting("ZoneKc")
        return // Skip drawing the input box since we just saved it
    }

    // 2. Draw the input box if the button was clicked
    if (state.kcBtn) {
        def targetValveId = state.kcBtn.toString()
        def hardwareSwitch = settings.valves?.find{it.id == targetValveId}
        def valveName = hardwareSwitch?.label ?: hardwareSwitch?.name ?: "Unknown Valve"
        def fallbackKc = settings.globalCropCoefficient != null ? settings.globalCropCoefficient : 0.8
        def currentKc = state.valves[targetValveId]?.kc ?: fallbackKc

        paragraph "<b>Set Crop Coefficient (Kc) for:</b> $valveName"
        input "ZoneKc", "decimal", title: "Crop Coefficient (Kc) (Press Enter to Save)", submitOnChange: true, width: 4, range: "0.1..1.5", defaultValue: currentKc, newLineAfter: true
    }
}

def displayZoneAppRate() {
    // 1. Intercept the page reload from hitting 'return'
    if (state.appRateBtn && settings.ZoneAppRate != null && settings.ZoneAppRate.toString() != "") {
        def targetValveId = state.appRateBtn.toString()
        def clonedValves = state.valves.collectEntries { k, v -> [k, v.clone()] }
        clonedValves[targetValveId].appRate = settings.ZoneAppRate
        state.valves = clonedValves
        
        state.remove("appRateBtn")
        app.removeSetting("ZoneAppRate")
        return // Skip drawing the input box since we just saved it
    }

    // 2. Draw the input box if the button was clicked
    if (state.appRateBtn) {
        def targetValveId = state.appRateBtn.toString()
        def hardwareSwitch = settings.valves?.find{it.id == targetValveId}
        def valveName = hardwareSwitch?.label ?: hardwareSwitch?.name ?: "Unknown Valve"
        def currentAppRate = state.valves[targetValveId]?.appRate

        paragraph "<b>Set Application Rate for:</b> $valveName"
        input "ZoneAppRate", "decimal", title: "Application Rate (in/hr) (Press Enter to Save)", submitOnChange: true, width: 4, range: "0.1..10.0", defaultValue: currentAppRate, newLineAfter: true
    }
}

def selectDayGroup() {
    if(state.dayGrpBtn) {
        List availableGroups = state.dayGroup.keySet().collect() 
        def currentSelection = state.valves[state.dayGrpBtn]?.dayGroup ?: []
        
        input "DayGroup", "enum", title: "Sprinkler Group", submitOnChange: true, width: 4, options: availableGroups, defaultValue: currentSelection, newLineAfter: true, multiple: true
        
        if(DayGroup) {
            state.valves[state.dayGrpBtn].dayGroup = DayGroup
            state.remove("dayGrpBtn")
            app.removeSetting("DayGroup")
            paragraph "<script>{changeSubmit(this)}</script>"
        }
    }
}

def addDayGroup(eventTrigger = null) {
    def templateMap = [ '1': false, '2': false, '3': false, '4': false, '5': false, '6': false, '7': false, "s": "P", "name": "", "ot": false, "ra": false, "duraTime": null, "startTime": null ]
    
    // Clone map to force Hubitat database serialization
    def clonedGroups = [:]
    state.dayGroup.each { k, v -> clonedGroups[k] = v }
    
    def targetIndex = (clonedGroups.size() + 1).toString() 
    clonedGroups[targetIndex] = templateMap.clone()
    
    state.dayGroup = clonedGroups
}

def remDayGroup(eventTrigger = null) {
    if (state.dayGroup.size() > 1) {
        def targetGroupId = eventTrigger.toString()
        
        def clonedGroups = [:]
        state.dayGroup.each { k, v -> clonedGroups[k] = v }
        
        if (clonedGroups.containsKey(targetGroupId)) { 
            clonedGroups.remove(targetGroupId) 
        } 
        
        def reorderedGroups = [:]
        def oldToNewMapping = [:] 
        def loopCounter = 1
        
        // Re-index remaining groups sequentially and create a translation map
        clonedGroups.keySet().collect { it.toInteger() }.sort().each { oldKeyInt ->
            def oldKey = oldKeyInt.toString()
            def newKey = loopCounter.toString()
            reorderedGroups[newKey] = clonedGroups[oldKey]
            oldToNewMapping[oldKey] = newKey
            loopCounter++
        }
        
        // Repair cascading 'after_X' start times to match the shifted IDs
        reorderedGroups.each { key, data ->
            if (data.startTime?.startsWith("after_")) {
                def chainedTarget = data.startTime.split('_')[1]
                if (chainedTarget == targetGroupId) {
                    data.startTime = null // The group it followed was deleted, reset to manual time
                } else if (oldToNewMapping[chainedTarget]) {
                    data.startTime = "after_${oldToNewMapping[chainedTarget]}" // Update the pointer
                }
            }
        }
        
        // Repair the valve mappings so hardware isn't triggered by the wrong group
        def clonedValves = [:]
        state.valves.each { valveId, valveData ->
            def updatedValveGroups = []
            valveData.dayGroup.each { oldGrp ->
                if (oldGrp != targetGroupId && oldToNewMapping[oldGrp]) {
                    updatedValveGroups.add(oldToNewMapping[oldGrp])
                }
            }
            valveData.dayGroup = updatedValveGroups
            clonedValves[valveId] = valveData
        }
        
        // Force top-level serialization for both arrays
        state.dayGroup = reorderedGroups
        state.valves = clonedValves
    }
}


// -----------------------------------------------------------------------------
// Global Variable Rendering & Processing
// -----------------------------------------------------------------------------

String displayMonths() {
    String tableHtml = "<i>Assume that Valve Duration is 100% and adjust that timing by these percentages, monthly. Valve Duration is reduced to the percentage defined for the month in which it runs. (20 seconds is the valve's minimum duration.) </i><p>"
    tableHtml += "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .tstat-col td,.tstat-col th { padding:8px 8px;text-align:center;font-size:12px} .tstat-col td {font-size:15px }" +
        "</style><div style='overflow-x:auto'><table class='mdl-data-table tstat-col' style=';border:2px solid black'>" +
        "<thead><tr style='border-bottom:2px solid black'><th>Jan</th><th>Feb</th><th>Mar</th><th>Apr</th><th>May</th><th>Jun</th><th>Jul</th><th>Aug</th><th>Sep</th><th>Oct</th><th>Nov</th><th>Dec</th></tr></thead>"
    tableHtml += "<tr style='color:black'border = 1>" 
    state.month2month.keySet().sort { it.toInteger() }.each { monthIndex ->
        String columnHtml = buttonLink("m${monthIndex}", "${state.month2month[monthIndex]}", "purple")
        tableHtml += "<th>$columnHtml</th>"
    }
    tableHtml += "</tr></table></div>"
    return tableHtml
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
    paragraph "\n<b>Overtemperature Sensor</b>"
    input "outdoorTempDevice", "capability.temperatureMeasurement", title: "Select which device?", multiple: false, required: false, submitOnChange: true
    input "maxOutdoorTemp", "number", title: "<i>Enter the Maximum temperature, beyond which, conditional Timetables will be invoked.</i>", defaultValue: maxOutdoorTemp, multiple: false, required: false, submitOnChange: true
}

def selectRainDevice() {
    paragraph "\n<b>Rain Device Selection</b>"
    input "rainDeviceOutdoor", "capability.waterSensor", title: "Select Rain Sensor <i>(If this sensor reports 'wet', watering is paused)</i>", multiple: false, required: false, submitOnChange: true
    
    if (settings.rainDeviceOutdoor) {
        def deviceAttributes = [:]
        def attributeCounter = 1
        // Simplified attribute collection for a single device object
        def attributeList = settings.rainDeviceOutdoor.supportedAttributes.collect { it?.toString()?.toLowerCase() }?.toSet()?.sort()
        
        attributeList.each { attributeString -> deviceAttributes[attributeCounter++] = "$attributeString" }
        input "selectRainAttribute", "enum", options: deviceAttributes, title: "<i>Which Attribute indicates there was enough rain to skip a cycle?</i>", defaultValue: settings.selectRainAttribute, multiple: false, required: false, submitOnChange: true
        state.currentRainAttribute = deviceAttributes[settings.selectRainAttribute as Integer]
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
    
    if (outdoorTempDevice) { 
        subscribe(outdoorTempDevice, "temperature", "recvOutdoorTempHandler") 
        
        def currentAmbientTemp = outdoorTempDevice.currentValue("temperature")?.toString()
        def configuredMaxTemp = settings.maxOutdoorTemp?.toString()
        
        if (currentAmbientTemp?.isNumber() && !configuredMaxTemp?.isNumber()) {
            logInfo {"No Maximum Temperature specified. Defaulting to current ambient temperature: ${currentAmbientTemp}°"}
            app.updateSetting("maxOutdoorTemp", [value: currentAmbientTemp, type: "number"])
            configuredMaxTemp = currentAmbientTemp
        }
        
        if (currentAmbientTemp?.isNumber() && configuredMaxTemp?.isNumber()) {
            state.todayHighTemp = state.todayHighTemp ?: currentAmbientTemp
            state.overTempToday = new BigDecimal(currentAmbientTemp) > new BigDecimal(configuredMaxTemp)
            logDebug {"Initial OutdoorTemp evaluation. Current: ${currentAmbientTemp}, Max: ${configuredMaxTemp}. overTempToday: ${state.overTempToday}"}
        } else {
            state.overTempToday = false
        }
    } else {
        state.overTempToday = false
    }
    
    state.rainDeviceData = [:]
    def rainAttributeString = state.currentRainAttribute?.toString()
    
    if (settings.rainDeviceOutdoor && rainAttributeString) {
        def sensorDevice = settings.rainDeviceOutdoor
        def sensorName = sensorDevice.label ?: sensorDevice.name
        def sensorId = sensorDevice.id.toString()
        def currentSensorValue = sensorDevice.currentValue(rainAttributeString)?.toString()
        
        state.rainDeviceData[sensorId] = [value: currentSensorValue, name: sensorName]
        subscribe(sensorDevice, rainAttributeString, "recvOutdoorRainHandler")
        
        state.rainHold = currentSensorValue?.toLowerCase() == "wet"
        logDebug {"Initial OutdoorRain evaluation. Active sensor monitored. rainHold: ${state.rainHold}"}
    } else {
        state.rainHold = false
    }

    updateMyLabel(2)
    scheduleNext()
}

def initialize() { }

def init(reasonCode) {
    switch(reasonCode) {            
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
            
            valves?.each { hardwareDevice -> if(!state.valves["$hardwareDevice.id"]) { state.valves["$hardwareDevice.id"] = ['dayGroup':['1'], 'etMode':true] } } 
            break; 
    }
}

void appButtonHandler(btnTrigger) {
    // Intercept explicit saves before UI variables are wiped
    if (btnTrigger == "DoneDayGroupBtn" && state.dayGrpBtn) {
        def targetValveId = state.dayGrpBtn.toString()
        def clonedValves = [:]
        state.valves.each { k, v -> clonedValves[k] = v }
        
        def userSelections = settings.DayGroup ?: []
        if (userSelections && !(userSelections instanceof List)) { userSelections = [userSelections] }
        
        clonedValves[targetValveId].dayGroup = userSelections
        state.valves = clonedValves
    }

    state.remove("dayGroupBtn")
    state.remove("dayGrpBtn")
    state.remove("kcBtn")
    state.remove("appRateBtn")
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
    app.removeSetting("DayGroup")
    app.removeSetting("ZoneKc")
    app.removeSetting("ZoneAppRate")

	if (btnTrigger.startsWith("e")) {
        def targetValveId = btnTrigger.minus("e")
        def clonedValves = state.valves.collectEntries { k, v -> [k, v.clone()] }
        def currentState = clonedValves[targetValveId].containsKey('etMode') ? clonedValves[targetValveId].etMode : true
        clonedValves[targetValveId].etMode = !currentState
        state.valves = clonedValves
        return
    }

    if      ( btnTrigger == "btnSchEna")           toggleEnaSchBtn()    else if ( btnTrigger == "btnUsda")             getSoilTypeFromUSDA()
    else if ( btnTrigger == "addDGBtn")            addDayGroup()
    else if ( btnTrigger.startsWith("m")        )  state.dispMonthBtn = btnTrigger.minus("m")
    else if ( btnTrigger.startsWith("rem")      )  remDayGroup(btnTrigger.minus("rem")) 
    else if ( btnTrigger.startsWith("n")        )  state.duraTimeBtn = btnTrigger.minus("n")
    else if ( btnTrigger.startsWith("r")        )  state.dayGrpBtn = btnTrigger.minus("r")
    else if ( btnTrigger.startsWith("k")        )  state.kcBtn = btnTrigger.minus("k")
    else if ( btnTrigger.startsWith("a")        )  state.appRateBtn = btnTrigger.minus("a")
    else if ( btnTrigger.startsWith("t")        )  state.startTimeBtn = btnTrigger.minus("t")
    else if ( btnTrigger.startsWith("w")        )  state.dayGroupBtn = btnTrigger.minus("w")
    else if ( btnTrigger.startsWith("o")        )  state.overTempBtn = btnTrigger.minus("o")
    else if ( btnTrigger.startsWith("x")        )  state.eraseTime = btnTrigger.minus("x")
}

// -----------------------------------------------------------------------------
// Schedule Execution Logic
// -----------------------------------------------------------------------------

def reschedule() {
    unschedule(reschedule)
    schedule('7 7 0 ? * *', reschedule) 
    state.overTempToday = false 
    state.remove("todayHighTemp")
    runIn(15, scheduleNext)
}

def scheduleNext() {
    String appLabel = app.label
    if (app.label.contains('<span ')) { appLabel = app.label.substring(0, app.label.indexOf('<span ')) }
    
    def hasZeroConfiguration = state.dayGroup.any { groupId, groupData -> groupData.any { it.value.toString() == "0" } } || state.valves?.isEmpty()
    if (hasZeroConfiguration) {
        logWarn {"Please set Time and Duration"}
        return
    }
    
    unschedule(reschedule)
    unschedule(schedHandler)
    schedule('7 7 0 ? * *', reschedule) 
    logInfo {"Checking $appLabel Schedule."}
    
    Calendar calendar = Calendar.getInstance();
    def currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
    def futureTimings = buildTimings(currentDayOfWeek)
    if (!futureTimings) {
        logWarn {"Nothing scheduled for $appLabel Today."}
        return
    }

    if (!schEnable) { logWarn {"Schedule Paused for $appLabel."}; return }
    if (state.rainHold) { logWarn {"Rain Hold possible for $appLabel Today."} }
    
	Date currentDate = new Date()
    String formattedTimeNow = currentDate.format("HH:mm")
    def hasValidSchedule = false
    def startHour, startMinute, targetGroupId

    for (scheduleEntry in futureTimings) {
        targetGroupId = scheduleEntry.key
        if (scheduleEntry.startTime.startsWith("after_")) continue
        (startHour, startMinute) = scheduleEntry.startTime.split(':')
        if (formattedTimeNow.replace(':', '').toInteger() >= scheduleEntry.startTime.replace(':', '').toInteger()) continue
        hasValidSchedule = true
        break;
    }

    if (hasValidSchedule) { 
        schedule("0 ${startMinute} ${startHour} ? * *", schedHandler, [data: ["targetGroupId":"$targetGroupId"]]) 
        logInfo {"$appLabel scheduled today."}
        logDebug {"Scheduled events list for today: ${futureTimings}"}
    } else {
        logInfo {"Nothing scheduled for $appLabel today."}
    }
}

def schedHandler(payloadData) {
    unschedule(schedHandler)
    String appLabel = app.label
    if (app.label.contains('<span ')) { appLabel = app.label.substring(0, app.label.indexOf('<span ')) }
    
    logInfo {"Running $appLabel Schedule."}
    String activeGroupId = payloadData["targetGroupId"] as String
    def baseDurationMinutes = state.dayGroup."$activeGroupId".duraTime

    if(state.dayGroup[activeGroupId].ot && !state.overTempToday) {
        logInfo {"No Over Temperature today, skipping."}
        runIn(60, scheduleNext)
        return
    }

    if (baseDurationMinutes == 0) {
        logInfo {"Duration of 0, skipping."}
        runIn(60, scheduleNext)
        return	
    }

    if (state.rainHold) { 
        logWarn {"Rain Hold - schedule skipped for $appLabel Today."}
        runIn(60, scheduleNext)
        return
    }

    def pendingSwitches = state.valves.findAll { it.value.dayGroup.contains(activeGroupId) }.keySet()
    if (!pendingSwitches) {
        logInfo {"No Switch in Day Group."}
        runIn(60, scheduleNext)
        return
    }
    logDebug {"schedHandler: target $activeGroupId, queue: $pendingSwitches"} 

    String activeSwitchId = pendingSwitches[0] as String
    if (activeSwitchId != null) { pendingSwitches = pendingSwitches.tail() }

    def hardwareSwitch = settings.valves?.find{it.id == "$activeSwitchId"}
    hardwareSwitch?.on()
    logInfo {"Valve switch ${hardwareSwitch?.label ?: hardwareSwitch?.name} on."}
    
    state.inCycle = true
    atomicState.cycleStart = now()
    updateMyLabel(3)

    baseDurationMinutes = state.dayGroup."$activeGroupId".duraTime
    def currentMonthInteger = new Date().format("M") 	
    def seasonalMultiplier = state.month2month ? state.month2month[currentMonthInteger].toDouble() / 100 : 1  
    def calculatedDurationSeconds = 60 * baseDurationMinutes * seasonalMultiplier
    def safeDurationSeconds = Math.max(calculatedDurationSeconds.toInteger(), 20) 
    
    logDebug {"runIn($safeDurationSeconds, scheduleDurationHandler, [activeSwitchId: $activeSwitchId, pendingSwitches: $pendingSwitches, targetGroupId: $activeGroupId])"}
    runIn(safeDurationSeconds, scheduleDurationHandler, [data: [activeSwitchId: "$activeSwitchId", durationSeconds: "$safeDurationSeconds", pendingSwitches: "$pendingSwitches", targetGroupId: "$activeGroupId"]]) 
}

def scheduleDurationHandler(payloadData) {
    unschedule(scheduleDurationHandler)
    String activeSwitchId = payloadData.activeSwitchId as String
    String activeGroupId = payloadData.targetGroupId as String
    def safeDurationSeconds = payloadData.durationSeconds.toInteger()
    def pendingSwitches = payloadData.pendingSwitches as String
    logDebug {"schedDurHandler: stopping switch $activeSwitchId, next queue: $pendingSwitches"}

    def hardwareSwitch = settings.valves?.find{it.id == "$activeSwitchId"}
    hardwareSwitch?.off()
    logInfo {"Valve switch ${hardwareSwitch?.label ?: hardwareSwitch?.name} off."}

    pauseExecution(20000)
    
    if (state.rainHold) {
        logWarn {"Rain Hold triggered mid-cycle. Aborting remaining zones."}
        state.inCycle = false
        atomicState.cycleEnd = now()
        runIn(30, scheduleNext)
        updateMyLabel(4)
        return
    }

    if (pendingSwitches != '[]') {
        pendingSwitches = pendingSwitches.replaceAll(/\[|\]/, '').split(',').collect { it.trim().toInteger() }
        String nextSwitchId = pendingSwitches[0] as String
        if (nextSwitchId != null) {
            pendingSwitches = pendingSwitches.tail()
            hardwareSwitch = settings.valves?.find{it.id == "$nextSwitchId"}
            hardwareSwitch?.on()
            logInfo {"Valve switch ${hardwareSwitch?.label ?: hardwareSwitch?.name} on."}
            runIn(safeDurationSeconds, scheduleDurationHandler, [data: [activeSwitchId: "$nextSwitchId", durationSeconds: "$safeDurationSeconds", pendingSwitches: "$pendingSwitches", targetGroupId: "$activeGroupId"]])
        }
    } else {
        Calendar calendar = Calendar.getInstance();
        def currentDayOfWeek = calendar.get(Calendar.DAY_OF_WEEK);
        Map cronWeekTranslation = [1:'7', 2:'1', 3:'2', 4:'3', 5:'4', 6:'5', 7:'6']
        def translatedDayIndex = cronWeekTranslation[currentDayOfWeek]

        def cascadedGroupData = state.dayGroup.find { groupId, groupData ->
            groupData[translatedDayIndex] == true && groupData.startTime == "after_${activeGroupId}"
        }

        if (cascadedGroupData) {
            logInfo {"Day Group $activeGroupId complete. Cascading to dependent Day Group ${cascadedGroupData.key}."}
            runIn(20, schedHandler, [data: ["targetGroupId": cascadedGroupData.key]])
        } else {
            state.inCycle = false
            atomicState.cycleEnd = now()
            runIn(30, scheduleNext)
            updateMyLabel(4)
        }
    }
}

def buildTimings(cronDayIndex) {
    Map cronWeekTranslation = [1:'7', 2:'1', 3:'2', 4:'3', 5:'4', 6:'5', 7:'6']
    def scheduledGroupKeys = state.dayGroup.findAll { groupId, groupData -> groupData[cronWeekTranslation[cronDayIndex]] == true }.keySet()
    return scheduledGroupKeys.collect { groupId -> [key: groupId, duraTime: state.dayGroup[groupId]?.duraTime, startTime: state.dayGroup[groupId]?.startTime]}.findAll { it.startTime != null }.sort { it.startTime.startsWith("after_") ? "99:99" : it.startTime } 
}

// -----------------------------------------------------------------------------
// Sensor Callbacks & API Methods
// -----------------------------------------------------------------------------

def recvOutdoorTempHandler(hardwareEvent) {
    def currentTempString = hardwareEvent?.value?.toString()
    def configuredMaxTemp = settings.maxOutdoorTemp?.toString()
    
    // Track the highest temperature recorded today
    if (currentTempString?.isNumber()) {
        def currentTempNum = new BigDecimal(currentTempString)
        def recordedHighNum = state.todayHighTemp ? new BigDecimal(state.todayHighTemp.toString()) : currentTempNum
        if (currentTempNum >= recordedHighNum) {
            state.todayHighTemp = currentTempString
        }
    }
    
    logDebug {"OutdoorTemp Event Received - Ambient: ${currentTempString}°, Max Limit: ${configuredMaxTemp}°. Current Latch State: ${state.overTempToday}"}

    if (!state.overTempToday) { 
        if (currentTempString?.isNumber() && configuredMaxTemp?.isNumber()) {
            state.overTempToday = new BigDecimal(currentTempString) > new BigDecimal(configuredMaxTemp) 
            if (state.overTempToday) {
                logInfo {"Maximum temperature threshold crossed. OverTemp schedules are unlocked for the remainder of the day."}
            }
        } else {
            logWarn {"OutdoorTemp evaluation bypassed: Malformed or null telemetry. Current: ${currentTempString}, Max: ${configuredMaxTemp}"}
        }
    }
}

def recvOutdoorRainHandler(hardwareEvent) {
    def eventDeviceId = hardwareEvent?.deviceId?.toString()
    def configuredSensorId = settings.rainDeviceOutdoor?.id?.toString()
    
    if (configuredSensorId && configuredSensorId == eventDeviceId) {
        state.rainDeviceData[eventDeviceId] = [ value: hardwareEvent?.value, name : hardwareEvent?.displayName ]
        
        // Direct boolean latch based on singular hardware endpoint
        state.rainHold = hardwareEvent?.value?.toString()?.toLowerCase() == "wet"
        
        logDebug {"OutdoorRain update from Device. rainHold: ${state.rainHold}"}
    }
}

def getSoilTypeFromUSDA() {
    state.geo = state.geo ?: [:]
    if(state.geo.lat == null || state.geo.lon == null) {
        state.geo.lat = location?.latitude
        state.geo.lon = location?.longitude
        logInfo {"Using lat/lon from hub location"}
    }
    def geoLat = state.geo?.lat
    def geoLon = state.geo?.lon
    if(!geoLat || !geoLon) {
        state.usdaSoilMsg = "Hub coordinates unavailable."
        logWarn {"${state.usdaSoilMsg}"}
        return
    }
    def soilDataPayload = getSoilData(geoLat, geoLon)
    if(!soilDataPayload || !soilDataPayload.textureMapped) {
        state.usdaSoilMsg = "No USDA data returned for (${geoLat},${geoLon})"
        logWarn {"${state.usdaSoilMsg}"}
        return
    }
    def soilMapped = soilDataPayload.textureMapped
    def soilRaw = soilDataPayload.textureRaw ?: "n/a"
    def soilHydgrp = soilDataPayload.hydgrp ?: "n/a"
    state.defaultSoilType = soilMapped
    state.defaultHydgrp = soilHydgrp
    state.usdaSoilMsg = "USDA soil detected: ${soilMapped} (USDA: ${soilRaw}, Group ${soilHydgrp})"
    logInfo {"Soil: ${state.usdaSoilMsg}"}
}

def getSoilData(geoLat, geoLon) {
    def geoDelta = 0.0001
    def polygonWkt = "POLYGON((${geoLon-geoDelta} ${geoLat-geoDelta},${geoLon+geoDelta} ${geoLat-geoDelta},${geoLon+geoDelta} ${geoLat+geoDelta},${geoLon-geoDelta} ${geoLat+geoDelta},${geoLon-geoDelta} ${geoLat-geoDelta}))"
    def sqlQuery = """SELECT TOP 1 mapunit.musym, mapunit.muname, component.compname, component.hydgrp, chtexturegrp.texdesc FROM mapunit INNER JOIN component ON component.mukey = mapunit.mukey INNER JOIN chorizon ON chorizon.cokey = component.cokey INNER JOIN chtexturegrp ON chtexturegrp.chkey = chorizon.chkey WHERE mapunit.mukey IN ( SELECT mukey FROM SDA_Get_Mukey_from_intersection_with_WktWgs84('${polygonWkt}') )"""
    def httpParams = [uri: "https://sdmdataaccess.nrcs.usda.gov/tabular/post.rest", contentType: "application/x-www-form-urlencoded", body: [query: sqlQuery], timeout: 20]

    try {
        def responseText = ''
        httpPost(httpParams) { httpResponse ->
            def responsePayload = httpResponse?.data
            def responseClassName = responsePayload?.class?.name ?: ''
            responseText = (responseClassName.contains('InputStream') || responseClassName.contains('Reader')) ? responsePayload?.getText('UTF-8') : responsePayload?.toString()
        }
        if (!responseText) { logWarn {"getSoilData(): empty SDA response for (${geoLat},${geoLon})"}; return null }
        
        responseText = responseText.substring(responseText.indexOf("<"))
        def tableMatcher = responseText =~ /(?s)<Table>(.*?)<\/Table>/
        if (!tableMatcher.find()) { logWarn {"getSoilData(): no <Table> block in SDA response"}; return null }
        
        def tableXmlString = "<Table>${tableMatcher.group(1)}</Table>"
        tableXmlString = tableXmlString.replaceAll(/&(?![a-zA-Z]+;|#\d+;)/, '&amp;')
        def xmlPayload = new XmlSlurper(false, false).parseText(tableXmlString)
        
        def attrMusym = xmlPayload?.musym?.text() ?: ''
        def attrMuname = xmlPayload?.muname?.text() ?: ''
        def attrCompname = xmlPayload?.compname?.text() ?: ''
        def attrHydgrp = xmlPayload?.hydgrp?.text() ?: ''
        def attrTexdesc = xmlPayload?.texdesc?.text() ?: ''
        if (!attrTexdesc && !attrMuname){ logWarn {"getSoilData(): no soil data for (${geoLat},${geoLon})"}; return null }
        return [musym: attrMusym, muname: attrMuname, compname: attrCompname, hydgrp: attrHydgrp, textureRaw: attrTexdesc, textureMapped: mapSoilTextureToClass(attrTexdesc)]
    } catch(exception) { logWarn {"getSoilData() SDA error: ${exception.message}"}; return null }
}

def mapSoilTextureToClass(String soilTexture) {
    if(!soilTexture) return "Loam"
    def formattedTexture = soilTexture.toLowerCase().trim()
    switch(true) {
        case {formattedTexture == "sand" || formattedTexture.contains("coarse sand") || formattedTexture.contains("fine sand")}: return "Sand"
        case {formattedTexture.contains("loamy sand") || formattedTexture.contains("sandy loam") || formattedTexture.contains("fine sandy loam")}: return "Loamy Sand"
        case {formattedTexture.contains("loam") || formattedTexture.contains("silt loam") || formattedTexture.contains("silty clay loam") || formattedTexture.contains("sandy clay loam")}: return "Loam"
        case {formattedTexture.contains("clay loam") || formattedTexture.contains("silty clay") || formattedTexture.contains("sandy clay")}: return "Clay Loam"
        case {formattedTexture == "clay" || formattedTexture.contains("heavy clay") || formattedTexture.contains("vertisol")}: return "Clay"
        default: return "Loam"
    }
}

// -----------------------------------------------------------------------------
// UI Utilities
// -----------------------------------------------------------------------------

void updateMyLabel(lifecycleEvent) {
    String baseLabel = app.label?.with { contains('<span ') ? substring(0, indexOf('<span ')) : it } ?: ""
    String statusHtml = ""
    if (settings.schEnable != true) { statusHtml = '<span style="color:Crimson"> (inactive)</span>' } 
    else if (atomicState.isPaused) { statusHtml = '<span style="color:Crimson"> (paused)</span>' } 
    else if (state.inCycle) {
        def stringBeganAt = atomicState.cycleStart ? "started ${fixDateTimeString(atomicState.cycleStart)}" : "running"
        statusHtml = "<span style=\"color:Green\"> (${stringBeganAt})</span>"
    } else if (state.inCycle != null && !state.inCycle) {
        def stringEndedAt = atomicState.cycleEnd ? "finished ${fixDateTimeString(atomicState.cycleEnd)}" : "idle"
        statusHtml = "<span style=\"color:Green\"> (${stringEndedAt})</span>"
    }
    String consolidatedLabel = baseLabel + statusHtml
    if (app.label != consolidatedLabel) { app.updateLabel(consolidatedLabel) }
}

String fixDateTimeString(eventTimestamp) {
    def targetDate = new Date(eventTimestamp)
    def dateToday = new Date().clearTime()
    def dateYesterday = new Date(dateToday.time - 1 * 24 * 60 * 60 * 1000) 
    def dateTomorrow = new Date(dateToday.time + 1 * 24 * 60 * 60 * 1000) 

    String dateString = ''
    boolean renderTime = true

    if (targetDate.clearTime() == dateToday) { dateString = 'today' } 
    else if (targetDate.clearTime() == dateYesterday) { dateString = 'yesterday' } 
    else if (targetDate.clearTime() == dateTomorrow) { dateString = 'tomorrow' } 
    else if (targetDate.format('yyyy-MM-dd') == '2035-01-01') { dateString = 'a long time from now'; renderTime = false } 
    else { dateString = "on ${targetDate.format('MM-dd')}" }

    targetDate = new Date(eventTimestamp)
    String timeString = renderTime ? targetDate.format('h:mma').toLowerCase() : ''
    return timeString ? "${dateString} at ${timeString}" : dateString
}

void logDebug(Closure logMessage) {
    if (settings.debugEnable) { log.debug "${logMessage()}" }
}

def logWarn(Closure logMessage) { 
    log.warn "${logMessage()}"
}

def logInfo(Closure logMessage) {
    if (settings.infoEnable) { log.info "${logMessage()}" }
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

String buttonLink(String buttonName, String linkText, hexColor = "#1A77C9", fontSize = "15px") {
    "<div class='form-group'><input type='hidden' name='${buttonName}.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' style='color:$hexColor;cursor:pointer;font-size:$fontSize'>$linkText</div></div><input type='hidden' name='settings[$buttonName]' value=''>"
}

def sectFormat(formatType, textString=""){ 
    if(formatType == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
    if(formatType == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${textString}</h2>"
    if(formatType == "subTitle") return "<p style='color:#1A77C9;font-weight: bold; font-size: 1.4em;'>${textString}</p>"
}

def displayHeader() {
    section (sectFormat("title", "SprinklerSchedulePlus")) {
        paragraph "<div style='color:#1A77C9;text-align:right;font-weight:small;font-size:9px;'>Current Version: ${version()} - &copy; 2023 C Steele</div>"
        paragraph "\n<hr style='background-color:#1A77C9; height: 1px; border: 0;'></hr>"
    }
}

String menuHeader(headerText){"<div style=\"width:102%;background-color:#696969;color:white;padding:4px;font-weight: bold;box-shadow: 1px 2px 2px #bababa;margin-left: -10px\">${headerText}</div>"}