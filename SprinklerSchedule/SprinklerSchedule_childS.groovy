/* 
=============================================================================
Hubitat Elevation Application
Sprinkler Schedule (child application) Sprinkler Switch Timetable

    Inspiration: Lighting Schedules https://github.com/matt-hammond-001/hubitat-code
    Inspiration: github example from Hubitat of lightsUsage.groovy
    This fork: Sprinkler Schedules https://github.com/csteele-PD/Hubitat-public/tree/master/SprinklerSchedule

-----------------------------------------------------------------------------
This code is licensed as follows:

	Portions:
	 	Copyright (c) 2022 Hubitat, Inc.  All Rights Reserved Bruce Ravenel 

	BSD 3-Clause License
	
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
 *
 *
 * relifram: v1.0.21 bug fixes to interface and scheduling
 * relifram: v1.0.20 Added sequential processing for rain tables
 * relifram: v1.0.19 Corrected recvOutdoorRainHandler to use deviceId. Added feature to allow system to abort cycle if rainhold is set 
 * csteele: v1.0.15	Add Overlap Check
 * csteele: v1.0.14	Accept Soil Type from Parent
 * csteele: v1.0.13	Merge dayGroup tables ( displayTable() merged into displayDayGroups() )
 * csteele: v1.0.12	fix removal of Master day group during masterGroupMerge(masterDayGroupIn)
 *                       remove "[]" from around dayGroup selection in displayGrpSched
 * csteele: v1.0.11	fix NPE in setOutdoorRain()
 * csteele: v1.0.10	remove entries from state.valves that aren't in valves.
 * csteele: v1.0.9	hide page when the Timetable is disabled/inactive.
 *                       clean up unused methods: initialized(), installed(), uninstalled()
 *				 refactored logging into using closures.
 * csteele: v1.0.8	corrected updateMyLabel().
 * csteele: v1.0.7	initialize state.rainDeviceOutdoor in setOutdoorRain.
 * csteele: v1.0.6	Skip when no valves in a schedule.
 * csteele: v1.0.5	Adjustments to recvOutdoorRainHandler().
 * csteele: v1.0.4	After carefully fixing schEnable display, it wasn't used in scheduleNext logic. 
 * csteele: v1.0.3	Allow multiple Rain Sensors to be integrated.
 * csteele: v1.0.2	corrected schEnable, so that enaDis is correct initially.
 *                       null safe currentValve?.label/name
 *                       refactor rainHold to be by timetable,
 *                       don't offer rain hold if no rain sensor device is selected.
 *                       remove rainHold reset at midnight.
 * csteele: v1.0.1	cosmetic valve/switch word adjustments 
 * csteele: v1.0.0	Converted to capability.switch from valve  
 *
 */
 
	public static String version()      {  return "v1.0.15"  }

definition(
	name: "Sprinkler Switch Timetable",
	namespace: "csteele",
	parent: "csteele:Sprinkler Schedule Manager",
	author: "C Steele",
	description: "Controls valve relays to a timing schedule",
	importUrl: "https://raw.githubusercontent.com/csteele-PD/Hubitat-public/refs/heads/master/SprinklerSchedule/SprinklerSchedule_childS.groovy",
	documentationLink: "https://www.hubitatcommunity.com/QuikRef/sprinklerScheduleManagerInfo/index.html",
	iconUrl: "",
	iconX2Url: "",
)

preferences {
	page(name: "main")
}

def main(){
	init(1) 	// during first time install of child, along with installCheck(), pre-populate any un-initialized elements 
	dynamicPage(name: "main", title: "", uninstall: true, install: true){
		updateMyLabel(1)
		displayHeader()
		state.appInstalled = app.getInstallationState() // validate that the Done button has been clicked the first time
		if (state.appInstalled != 'COMPLETE') return installCheck() 

      	section(menuHeader("General")) {
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

		if (schEnable) {
			section(menuHeader("Timetable Status & Logging")) {
				if (valves) {
					currentMonth = new Date().format("M") 	// Get the current month as a number (1-12)
					currentMonthPercentage = state.month2month ? state.month2month[currentMonth].toDouble() : 1  // Lookup the percent in month2month or 1 
				 // provide some feedback on which valves are On
				 	String str = "<div style='background-color: rgba(73, 163, 125, 0.3);'>"	
					if (state.month2month) {
						str += "<b>Adjust valve timing</b> by Month is active. Current month is: <b>$currentMonthPercentage%</b><br>" +
						"<b>Rain hold</b> is $state.rainHold<br>" +
						"<b>Soil</b> is $state.defaultSoilType<br>"
					}
					if (state.overTempToday) { str += "Sometime today, the outside temperature <b>exceeded</b> the limit you set of $state.maxOutdoorTemp and any Over Temp schedules <b>will run.</b><br>" }
					str += valves?.collect { dev -> "<b>${dev.label ?: dev.name}</b> is ${dev.currentValue('valve', true) == 'on' ? 'On' : 'Off'}"}?.join(', ') ?: ""
					str += "</div>"
					paragraph str
				}

				input "infoEnable", "bool",
					title: "Enable activity logging",
					required: false,
					defaultValue: true, width: 2
				input "debugEnable", "bool",
					title: "Enable debug logging", 
					required: false,
					defaultValue: false,
					submitOnChange: true, width: 2

				if (debugEnable) {
					input "debugTimeout", "enum", required: false, defaultValue: "0", title: "Automatic debug Log Disable Timeout?", width: 3,  \
					    	options: [ "0":"None", "1800":"30 Minutes", "3600":"60 Minutes", "86400":"1 Day" ]
				}
			}

			if (valves) {
				section(menuHeader("Schedule")) {

					paragraph "<b>Select Days into Groups</b>"
					paragraph displayDayGroups()		// display day-of-week groups - Section I & II
					  displayDuration()
					  displayStartTime()

					paragraph "<b>Select Valves into Day Groups</b>"
					paragraph displayGrpSched()		// display mapping of Valve to DayGroup - Section III
					  selectDayGroup()

					if (state.rainDeviceOutdoor != [:]) {
						def rainVars = ["0": "no rainHold"]
						state.rainDeviceOutdoor.each { key, info ->
							rainVars[key] = info.name
						}
						input "rainEnableDevice", "enum", 
							title: "<b>Choose the Rain Sensor for this Timetable</b><p><i>leave unselected for no Rain Hold</i></p>", 
							submitOnChange: true, 
							defaultValue: "0",
							options: rainVars
					}

					paragraph "\n<hr style='background-color:#1A77C9; height: 1px; border: 0;'></hr>"
				}
			}
		}
	}
}


/*
-----------------------------------------------------------------------------
Main Page handlers
-----------------------------------------------------------------------------
*/

String displayDayGroups() {	// display day-of-week groups - Section I
	//.20
	// Process pending duration and start time inputs before generating the UI
	if (state.duraTimeBtn && settings.DuraTime != null) {
		def duraTimeBtn = state.duraTimeBtn as Integer
		def masterSize = state.dayGroupMaster?.size() ?: 0
		def offset = (masterSize >= duraTimeBtn) 
		def nIndex = (offset) ? duraTimeBtn.toString() : (duraTimeBtn - masterSize).toString()
		if (offset) {
			if (state.dayGroupMaster.containsKey(nIndex)) { state.dayGroupMaster[nIndex].duraTime = settings.DuraTime }
		} else {
			if (state.dayGroup.containsKey(nIndex)) { state.dayGroup[nIndex].duraTime = settings.DuraTime }
		}
		state.remove("duraTimeBtn")
		app.removeSetting("DuraTime")
	}

	if (state.startTimeBtn) {
	    def mode = settings.StartMode ?: "time"
	    if ((mode == "time" && settings.StartTime) || (mode == "after" && settings.StartAfter)) {
    		def startTimeBtn = state.startTimeBtn as Integer
    		def masterSize = state.dayGroupMaster?.size() ?: 0
    		def offset = (masterSize >= startTimeBtn) 
    		def nIndex = (offset) ? startTimeBtn.toString() : (startTimeBtn - masterSize).toString()
    		
    		def newVal = mode == "time" ? Date.parse("yyyy-MM-dd'T'HH:mm:ss.SSSX", settings.StartTime).format('HH:mm') : settings.StartAfter
            
            // Collision Validation for Chained Execution
            boolean hasConflict = false
            if (mode == "after") {
                def conflict = state.dayGroupMerge.find { k, v -> v.startTime == newVal && k.toString() != startTimeBtn.toString() }
                if (conflict) {
                    hasConflict = true
                    state.chainError = "<b>Error:</b> Day Group ${conflict.key} is already configured to follow Group ${newVal.replace('after_', '')}. Multiple groups cannot follow a single group. Please select a different trigger."
                }
            }

            if (!hasConflict) {
                state.remove("chainError")
        		if (offset) {
        		    if (state.dayGroupMaster.containsKey(nIndex)) { state.dayGroupMaster[nIndex].startTime = newVal }
        		} else {
        		    if (state.dayGroup.containsKey(nIndex)) { state.dayGroup[nIndex].startTime = newVal }
        		}
        		state.remove("startTimeBtn")
        		app.removeSetting("StartTime")
        		app.removeSetting("StartMode")
        		app.removeSetting("StartAfter")
            }
	    }
	}
//.20end
	incM = state.dayGroupMaster?.size() ?: 0
	if(state.dayGroupBtn) {		// toggle the daily checkmarks 
		def btnStr = state.dayGroupBtn.toString()
		def len = btnStr.length()
		dgK = btnStr.substring(0, len - 1).toInteger() - incM // dayGroupBtn Key
		dgI = btnStr.substring(len - 1)   // dayGroupBtn value (mon-sun)
		state.dayGroup["$dgK"]["$dgI"] = !state.dayGroup["$dgK"]["$dgI"] // Toggle state
		state.remove("dayGroupBtn") // only once 
		logDebug {"displayDayGroups Item: $dgK.$dgI"}
	}
	if(state.overTempBtn) {			// toggle the overTemp checkmarks 
		dgK = state.overTempBtn.toInteger() - incM // overTempBtn Key
       	if (state.dayGroup.containsKey(dgK.toString())) { state.dayGroup[dgK.toString()].ot = !state.dayGroup[dgK.toString()].ot } // Toggle state
		state.remove("overTempBtn") 	// only once 
	}
	if (state.eraseTime) { // if the reset/erase button is clicked
		def eraseTime = state.eraseTime as Integer // which button (row) was clicked
		def masterSize = state.dayGroupMaster.size()
		def offset = (masterSize >= eraseTime) 
		def nIndex = (offset) ? eraseTime.toString() : (eraseTime - masterSize).toString()
			if (offset) {
			    if (state.dayGroupMaster.containsKey(nIndex)) { state.dayGroupMaster[nIndex].startTime = 0 }
			    if (state.dayGroupMaster.containsKey(nIndex)) { state.dayGroupMaster[nIndex].duraTime = 0 }
			} else {
			    if (state.dayGroup.containsKey(nIndex)) { state.dayGroup[nIndex].startTime = 0 }
			    if (state.dayGroup.containsKey(nIndex)) { state.dayGroup[nIndex].duraTime = 0 }
			}
		state.remove("eraseTime")
		app.removeSetting("eraseTime")
		paragraph "<script>{changeSubmit(this)}</script>"
	}

	masterGroupMerge()	// merge or riffle merge if there's a new mMap from Parent.

	String str = "<script src='https://code.iconify.design/iconify-icon/1.0.0/iconify-icon.min.js'></script>"
	str += "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .tstat-col td,.tstat-col th { padding:8px 8px;text-align:center;font-size:12px} .tstat-col td {font-size:15px }" +
		"</style><div style='overflow-x:auto'><table class='mdl-data-table tstat-col' style=';border:2px solid black'>" +
		"<thead><tr style='border-bottom:2px solid black'>" +
		"<th style='border-right:2px solid black'>Day Group</th>" +
		"<th>Mon</th>" +
		"<th>Tue</th>" +
		"<th>Wed</th>" +
		"<th>Thu</th>" +
		"<th>Fri</th>" +
		"<th>Sat</th>" +
		"<th>Sun</th>" +
		"<th colspan=2 style='color:#db7321;'>OverTemp</th>" +
		"<th>Start Time</th>" +
		"<th>Duration</th>" +
		"<th>Reset</th>" +
		"</tr></thead>"

	str += "<tr style='color:black'border = 1>" 
	String X = "<i class='he-checkbox-checked'></i>"
	String O = "<i class='he-checkbox-unchecked'></i>"
	String Plus = "<i class='ic--sharp-plus'>+</i>"
	String Minus = "<i class='trashcan'>-</i>"
	String addDayGroupBtn = buttonLink("addDGBtn", Plus, "#1A77C9", "")

	strRows = ""
	rowCount = 1

	state.dayGroupMaster.each {
	     k, dg -> 
	        str += strRows
	        str += "<th>$rowCount</th>"
	        for (int r = 1; r < 8; r++) { 
			String dayBoxN = noButtonLink("w$rowCount$r", O, "#49a37d", "")
			String dayBoxY = noButtonLink("w$rowCount$r", X,   "#49a37d", "")
	        	str += (dg."$r") ? "<th>$dayBoxY</th>" : "<th>$dayBoxN</th>" 
	        }
	      // no delete button on Master dayGroup rows.
		  str += "<th colspan=2>&nbsp;</th>"
		  //.20
		  String rawTime = state.dayGroupMerge[k]?.startTime
		  String sTimeDisp = rawTime ? (rawTime.startsWith("after_") ? "After Grp ${rawTime.split('_')[1]}" : rawTime) : "Set Time"
		  String sTime    = rawTime ? buttonLink("t$k", sTimeDisp, "black") : buttonLink("t$k", "Set Time", "green")
		  String dTime    = state.dayGroupMerge[k]?.duraTime
		  //.20end
 		  String duraTime = dTime ?  buttonLink("n$k", dTime, "purple") : buttonLink("n$k", "Select", "green")
		  String reset    = buttonLink("x$k", "<iconify-icon icon='bx:reset'></iconify-icon>", "black", "20px")
		  str += "<th>$sTime</th>" +
		  	"<th>$duraTime</th>" + 
		  	"<th title='Reset $k' style='padding:0px 0px'>$reset</th>"
		  strRows = "</tr><tr>" 
		  rowCount++
	}
	state.dayGroup.each {
	     k, dg -> 
	        str += strRows
	        str += "<th>$rowCount</th>"
	        for (int r = 1; r < 8; r++) { 
			String dayBoxN = buttonLink("w$rowCount$r", O, "#1A77C9", "")
			String dayBoxY = buttonLink("w$rowCount$r", X,   "#1A77C9", "")
	        	str += (dg."$r") ? "<th>$dayBoxY</th>" : "<th>$dayBoxN</th>" 
	        }
		  String remDayGroupBtn = buttonLink("rem$rowCount", "<i style=\"font-size:1.125rem\" class=\"material-icons he-bin\"></i>", "#1A77C9", "")
		  str += "<th>$remDayGroupBtn</th>"
		  String otBoxN = buttonLink("o$rowCount", O, "#db7321", "")
		  String otBoxY = buttonLink("o$rowCount", X,   "#db7321", "")
	      //.20
		  str += (dg."ot") ? "<th>$otBoxY</th>" : "<th>$otBoxN</th>" 
		  String rawTime = state.dayGroupMerge[rowCount]?.startTime
		  String sTimeDisp = rawTime ? (rawTime.startsWith("after_") ? "After Grp ${rawTime.split('_')[1]}" : rawTime) : "Set Time"
		  String sTime    = rawTime ? buttonLink("t$rowCount", sTimeDisp, "black") : buttonLink("t$rowCount", "Set Time", "green")
		  String dTime    = state.dayGroupMerge[rowCount]?.duraTime 
		  String duraTime = dTime ?  buttonLink("n$rowCount", dTime, "purple") : buttonLink("n$rowCount", "Select", "green")
		  //.20end
		  String reset    = buttonLink("x$rowCount", "<iconify-icon icon='bx:reset'></iconify-icon>", "black", "20px")
		  str += "<th>$sTime</th>" +
		  	"<th>$duraTime</th>" + 
		  	"<th title='Reset $k' style='padding:0px 0px'>$reset</th>"
		  strRows = "</tr><tr>" 
		  rowCount++
	}
	str += "</tr><tr>"
	str += "<th>$addDayGroupBtn</th><th colspan=4> <- Add new Day Group</th><th colspan=8>&nbsp;</th>"
	str += "</tr></table></div>"
	str
}


String displayGrpSched() {	// display mapping of Valve to DayGroup - Section III
	String str = "<script src='https://code.iconify.design/iconify-icon/1.0.0/iconify-icon.min.js'></script>"
	str += "<style>.mdl-data-table tbody tr:hover{background-color:inherit} .tstat-col td,.tstat-col th { padding:8px 8px;text-align:center;font-size:12px} .tstat-col td {font-size:15px }" +
		"</style><div style='overflow-x:auto'><table class='mdl-data-table tstat-col' style=';border:2px solid black'>" +
		"<thead><tr style='border-bottom:2px solid black'>" +
		"<th style='border-right:2px solid black'>Valve</th>" +
		"<th>Day Group</th>" +
		"</tr></thead>"

	state.valves.keySet().findAll { k -> !(k in valves.id) }.each { k -> state.valves.remove(k) }
	valves?.sort{it.displayName.toLowerCase()}.each {
	     dev ->
		  String devLink = "<a href='/device/edit/$dev.id' target='_blank' title='Open Device Page for $dev'>$dev"
		  String myDG = state.valves[dev.id].dayGroup.join(', ') // less the [] wrapper
		  String myDayGroup = myDG ? buttonLink("r$dev.id", myDG, "purple") : buttonLink("r$dev.id", "Select", "green")
		  str += "<tr style='color:black'>" +
		  	"<td style='border-right:2px solid black'>$devLink</td>" +
			"<td title='${myDG ? "Deselect $myDG" : "Select String Hub Variable"}'>$myDayGroup</td></tr>"
		  	"</tr>"
	}  
	str += "</table></div>"
	str
}

/*
-----------------------------------------------------------------------------
Display level handlers
-----------------------------------------------------------------------------
*/
//.21 changed all displayStartTime() - added input checking to prevent chaining one: many.
def displayStartTime() {
	if(state.startTimeBtn) {
		def startTimeBtn = state.startTimeBtn as Integer
		
		// Safely fetch current value using the unified dayGroupMerge map, handling both int and string keys
		def currentVal = state.dayGroupMerge[startTimeBtn]?.startTime ?: state.dayGroupMerge[startTimeBtn.toString()]?.startTime
		def isAfter = currentVal?.toString()?.startsWith("after_")

		// Determine the effective mode for UI rendering (Hubitat doesn't populate settings map from defaultValue on first pass)
		def effectiveMode = settings.StartMode ?: (isAfter ? "after" : "time")

		input "StartMode", "enum", title: "Start Trigger", submitOnChange: true, width: 3, options: ["time":"Scheduled Time", "after":"After Day Group"], defaultValue: isAfter ? "after" : "time"
		
		if (effectiveMode == "after") {
			def groupOpts = [:]
			state.dayGroupMerge.each { k, v -> if (k.toString() != startTimeBtn.toString()) groupOpts["after_${k}"] = "Day Group ${k}" }
			input "StartAfter", "enum", title: "Select Group", submitOnChange: true, width: 3, options: groupOpts, defaultValue: isAfter ? currentVal : null, newLineAfter: false
            
            if (state.chainError) {
                paragraph "<div style='color:red; padding-top: 4px'>${state.chainError}</div>"
            }
		} else {
			input "StartTime", "time",   title: "At This Time", submitOnChange: true, width: 3, defaultValue: isAfter ? null : currentVal, newLineAfter: false
		}

		input "DoneTime$state.startTimeBtn",  "button", title: "  Done with time  ", width: 2, newLineAfter: true
	}
}
//.21end


def displayDuration() {
	if(state.duraTimeBtn) {
		def duraTimeBtn = state.duraTimeBtn as Integer
		def masterSize = state.dayGroupMaster.size()
		def offset = (masterSize >= duraTimeBtn) 
		def nIndex = (offset) ? duraTimeBtn.toString() : (duraTimeBtn - masterSize).toString()
		def currentVal = offset ? state.dayGroupMaster[nIndex]?.duraTime : state.dayGroup[nIndex]?.duraTime
		
		input "DuraTime", "decimal", title: "Sprinkler Duration", submitOnChange: true, width: 4, range: "0..60", defaultValue: currentVal, newLineAfter: true
	}
}


def selectDayGroup() { // map valve to dayGroup
 	if(state.dayGrpBtn) {
		List vars = state.dayGroupMerge.keySet().collect() 

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
    def dayGroupTemplate = [
        '1': false, '2': false, '3': false, '4': false, '5': false, '6': false, '7': false, 
        "s": "P", "name": "", "ot": false, "ra": false, "duraTime": null, "startTime": null
    ] // new rows are all empty

    def dayGroupSize = state.dayGroup.size() // More efficient
    def newIndex = (dayGroupSize + 1).toString() // Ensures key consistency

    logDebug {"Adding another dayGroup map: $newIndex"}
    state.dayGroup[newIndex] = dayGroupTemplate.clone() // Clone to avoid reference issues
}


def remDayGroup(evt = null) {  	// remove a Local dayGroup & dayGroupSettings
	dayGroupSize = (state.dayGroupMerge ?: [:]).keySet().size()
	if (dayGroupSize > 1) {
		// Determine the key to delete
		keyToDelete = (evt.toInteger() - (state.dayGroupMaster ?: [:]).size()).toString()		
		logDebug {"remove another dayGroup map: $dayGroupSize, $keyToDelete, evt:$evt"}
		if (state.dayGroup.containsKey(keyToDelete)) { state.dayGroup.remove(keyToDelete) } 
		// Re-map keys to be sequential
		def dayGrpReOrder = [:]
		def counter = 1
		state.dayGroup.sort { it.key.toInteger() }.each { k, v ->
		    dayGrpReOrder["${counter}"] = v
		    counter++
		}
		state.dayGroup = dayGrpReOrder
	}
}


def masterGroupMerge(masterDayGroupIn = [:]) { // lots of deep copies of hashMaps
	// three part merge. Part 1: Decide which incoming "Master" is to be used and clone the days-of-week fields.
	def dayGroupMaster = [:]
	state.dayGroupMaster.each { k, v -> dayGroupMaster[k] = v.clone() } // deep copy (state vs local scope)

 	def dayGroupMerge = masterDayGroupIn ? masterDayGroupIn.collectEntries { k, v -> [k, v.clone()] }  : dayGroupMaster.collectEntries { k, v -> [k, v.clone()] }  ?: [:] // deep copy

	//  Part 2: overwrite setTime, DuraTime, name, ot and ra values from the child for the Master records. 
	if (masterDayGroupIn) {
		dayGroupMerge.each { k, v -> 
			if (state.dayGroupMaster.containsKey(k)) {
      	          def masterEntry = state.dayGroupMaster[k]
      	          v.startTime = masterEntry?.startTime ?: null
      	          v.duraTime = masterEntry?.duraTime ?: null
      	          v.name = masterEntry?.name ?: ""
      	          v.ot = masterEntry?.ot ?: false
      	          v.ra = masterEntry?.ra ?: false
      	      }
		}
		state.dayGroupMaster = [:]
		dayGroupMerge.each { k, v ->
			state.dayGroupMaster[k] = v.clone() // deep copy this new master for next pass.
		}
	}

	//  Part 3: independent of masterDayGroupIn vs state.dayGroupMaster, all the child dayGroup rows are cloned & keys renumbered.
	dayGroupSize = (dayGroupMerge ?: [:]).keySet().size()		// renumber, starting with the size of dayGroupMerge
	state.dayGroup.each { k, v -> // merge dayGroup into 
		dayGroupSize++
		dayGroupMerge[dayGroupSize] = v.clone() // deep copy
	}

	// then clone it for the next pass.
	state.dayGroupMerge = dayGroupMerge.collectEntries { k, v -> [k, v.clone()] } // deep copy
}


String buttonLink(String btnName, String linkText, color = "#1A77C9", font = "15px") {
	"<div class='form-group'><input type='hidden' name='${btnName}.type' value='button'></div><div><div class='submitOnChange' onclick='buttonClick(this)' style='color:$color;cursor:pointer;font-size:$font'>$linkText</div></div><input type='hidden' name='settings[$btnName]' value=''>"
}
String noButtonLink(String btnName, String linkText, color = "#1A77C9", font = "15px") {
	"<div class='form-group'></div><div><div style='color:$color;font-size:$font'>$linkText</div></div>"
}

void appButtonHandler(btn) {
	// only one button can be pressed, remove their states, since "btn" contains the only valid one.
	state.remove("dayGroupBtn")
	state.remove("dayGrpBtn")
	state.remove("doneTime")
	state.remove("duraTimeBtn") 
	//.20 
	state.remove("eraseTime")
	state.remove("overTempBtn")
	state.remove("startTimeBtn")
	state.remove("chainError")
	app.removeSetting("StartTime") 
	app.removeSetting("StartMode") 
	app.removeSetting("StartAfter") 
	app.removeSetting("DuraTime")
	//.20end

	if      ( btn == "btnSchEna")           toggleEnaSchBtn()
	else if ( btn == "addDGBtn")            addDayGroup()
	else if ( btn.startsWith("rem")      )  remDayGroup(btn.minus("rem")) 
	else if ( btn.startsWith("n")        )  state.duraTimeBtn = btn.minus("n")
	else if ( btn.startsWith("r")        )  state.dayGrpBtn = btn.minus("r")
	else if ( btn.startsWith("t")        )  state.startTimeBtn = btn.minus("t")
	else if ( btn.startsWith("w")        )  state.dayGroupBtn = btn.minus("w")
	else if ( btn.startsWith("o")        )  state.overTempBtn = btn.minus("o")
	else if ( btn.startsWith("x")        )  state.eraseTime = btn.minus("x")
}


/*
-------------------------------------------------------------------------------------------

---  UI code above here, Generic App code below.  Background Valve code further below.  ---

-------------------------------------------------------------------------------------------
*/
/*
-----------------------------------------------------------------------------
Logging output
-----------------------------------------------------------------------------
*/

void logDebug(Closure msg) {
    if (settings.debugEnable) { log.debug "${msg()}" }
}

def logWarn(Closure msg) { 
	log.warn "${msg()}"
}

def logInfo(Closure msg) {
    if (settings.infoEnable) { log.info "${msg()}" }
}


/*
-----------------------------------------------------------------------------
Standard handlers, and mode-change handler
-----------------------------------------------------------------------------
*/

def updated() {
	logDebug {"updated()"}
	unschedule (logsOff)
	if (debugEnable && debugTimeout.toInteger() >0) runIn(debugTimeout.toInteger(), logsOff)
	update()
}


def update() {
	updateMyLabel(2)
	scheduleNext()
}


//
// Parent sends values and devices to all Child apps.
//
def set2Month(monthIn) { 
	state.month2month = monthIn
	logInfo {"MonthIn update from Parent."}
}


def set2DayGroup(dayGroupIn) { 
	masterGroupMerge(dayGroupIn)
	logInfo {"DayGroup update from Parent."}
}


def setSoilType(val) {
	state.defaultSoilType = val
	logInfo {"Soil Type update from Parent, defaultSoilType: $state.defaultSoilType"}
}


def setOutdoorTemp(aTempDevice, dTemp) {
	state.outdoorTempDevice = aTempDevice.currentStates
	state.maxOutdoorTemp = dTemp
	def tempNow = aTempDevice.currentValue("temperature")
	state.overTempToday = ( tempNow > state.maxOutdoorTemp.toInteger() ) ? true : false
	logInfo {"OutdoorTemp update from Parent, tempNow: $tempNow."} 
	unsubscribe(recvOutdoorTempHandler)
	subscribe(aTempDevice, "temperature", recvOutdoorTempHandler)
}


def setOutdoorRain(aRainDevice, rainAttr) {
	unsubscribe(recvOutdoorRainHandler)
	state.rainDeviceOutdoor = [:]
	state.rainAttribute = rainAttr
	aRainDevice.each {ard -> 
		def ms1 = ard.label ?: ard.name		// use the Name when Label is blank.
		state.rainDeviceOutdoor[ard.id.toString()] = [
            	value: ard.currentValue(rainAttr),
            	name : ms1
		]
		logInfo {"OutdoorRain update from Parent, $ms1: ${ard.currentValue(rainAttr)}"}
		subscribe(ard, rainAttr, recvOutdoorRainHandler)
	}
	state.rainHold = rainEnableDevice && rainEnableDevice != "0" && state.rainDeviceOutdoor[rainEnableDevice]?.value?.toLowerCase() == "wet"
}


def getScheduleWindows() {
	masterGroupMerge()
	String baseLabel = app.label?.with {
		def flag = '<span '
		contains(flag) ? substring(0, indexOf(flag)) : it
	} ?: ""

	def windows = []
	if (!state.dayGroupMerge) return windows
	def valveOrder = settings.valves ?: []

	state.dayGroupMerge.each { k, v ->
		def startTime = v?.startTime
		def duraTime = v?.duraTime
		if (!startTime || !duraTime) return

		def valveIds = []
		valveOrder.each { dev ->
			if (state.valves[dev.id]?.dayGroup?.contains(k.toString())) {
				valveIds << dev.id.toString()
			}
		}
		int valveCount = valveIds.size()
		if (valveCount == 0) return

		int perValveSec = Math.max(((duraTime as BigDecimal) * 60G).toInteger(), 20)
		int totalSec = (valveCount * perValveSec) + ((valveCount - 1) * 20)

		(1..7).each { dayIdx ->
			if (v["$dayIdx"] == true) {
				windows << [
					timeline   : baseLabel,
					dayIndex   : dayIdx,
					dayGroup   : k.toString(),
					startTime  : startTime,
					startSec   : timeToSeconds(startTime),
					durationMin: duraTime,
					totalSec   : totalSec,
					valveCount : valveCount
				]
			}
		}
	}
	return windows
}


//
// Subscription Handlers
//
def recvOutdoorTempHandler(evt) {
	if (!state.overTempToday) { 	// if the temp goes over the limit, latch 'true' state til midnight reset
		state.overTempToday = new BigDecimal(evt.value) > new BigDecimal(state.maxOutdoorTemp) //  true : false 
		logDebug {"OutdoorTemp update from Device. overTempToday: $state.overTempToday"}
	}
}


def recvOutdoorRainHandler(evt) {
	if (rainEnableDevice == evt.deviceId.toString()) {
		state.rainDeviceOutdoor[evt.deviceId.toString()] = [
            	value: evt?.value,
            	name : evt?.displayName
		]
		state.rainHold = evt.value.toLowerCase() == "wet"
		logDebug {"OutdoorRain update from Device. rainHold: $state.rainHold"}
	}
}


def installCheck(){         
	state.appInstalled = app.getInstallationState() 
	if(state.appInstalled != 'COMPLETE'){
		state.paused = false
		app?.updateSetting("schEnable",[value:"true",type:"bool"])
		section{paragraph "Please hit 'Done' to Complete the install."}
	}
	else{
		logDebug {"$app.name is Installed Correctly"}
	}
}


def init(why) {
	switch(why) {            
		case 1: 
			if (!app.label) {
				app.updateLabel(app.name)
 				atomicState.appDisplayName = app.name
			}
			if(state.valves == null) state.valves = [:] 
			if(state.paused == null) state.paused = false // the switch visually is inverted from the logic. Default = true aka enabled/not paused.
			if(state.inCycle == null) state.inCycle = false
			if(state.overTempToday == null) state.overTempToday = false 
			if(state.rainHold == null) state.rainHold = false
			if(state.dayGroup == null) state.dayGroup = ['1': ['1':true, '2':true, '3':true, '4':true, '5':true, '6':true, '7':true, "s": "P", "name": "", "ot": false, "ra": false, "duraTime": null, "startTime": null ] ] // initial row
			if(state.rainDeviceOutdoor == null) state.rainDeviceOutdoor = [:] 
			if(state.defaultSoilType == null) state.defaultSoilType = "Unknown"

			if(state.month2month == null) state.month2month = [:]
			if(state.dayGroupMaster == null) state.dayGroupMaster = [:]
			valves.each { dev -> if(!state.valves["$dev.id"]) { state.valves["$dev.id"] = ['dayGroup':['1']] } } 
			break; 
	}
}


/*
-------------------------------------------------------------------------------------------

---  Generic App code above here, Background Valve on/off code below.  --------------------

-------------------------------------------------------------------------------------------
*/
/*
-----------------------------------------------------------------------------
   Whenever there is a Timetable change/update
-----------------------------------------------------------------------------
*/

def reschedule() {		// midnight run to setup first schedule of the day.
	unschedule(reschedule)
	schedule('7 7 0 ? * *', reschedule) // reschedule the midnight run to schedule that day's work.
	state.overTempToday = false // once a day, midnight, reset the over temp indicator
	runIn(15, scheduleNext)
}


def scheduleNext() {
	String myLabel = app.label
	if (app.label.contains('<span ')) {
		myLabel = app.label.substring(0, app.label.indexOf('<span '))
	}
	hasZero = state.dayGroupMerge.any { key, value -> value.any { it.value.toString() == "0" } } || state.valves?.isEmpty()
	if (hasZero) {
		logWarn {"Please set Time and Duration"}
		return
	}
	
// Clear all pending scheduled executions to prevent orphans when the table updates
	unschedule(reschedule)
	unschedule(schedHandler)
	schedule('7 7 0 ? * *', reschedule) // reschedule the midnight run to schedule that day's work.

	logInfo {"Checking $myLabel Schedule."}
	Calendar calendar = Calendar.getInstance();
	def cronDay = calendar.get(Calendar.DAY_OF_WEEK);

	timings = buildTimings(cronDay)
	if (!timings) {
 		logWarn {"Nothing scheduled for $myLabel Today."}
		return
	}

	if (!schEnable) {
		logWarn {"Schedule Paused for $myLabel."}
		return
	}

	if (state.rainHold && rainEnableDevice && rainEnableDevice != "0") {
		logWarn {"Rain Hold possible for $myLabel Today."}
	}

	Date date = new Date()
	String akaNow = date.format("HH:mm")

	//.21 - execute the next scheduled item, even if in the very near future.
	hasSched = false
	for (timN in timings) {
	    sk = timN.key			// index into dayGroupMerge for Duration
	    if (timN.startTime.startsWith("after_")) continue

	    (sth, stm) = timN.startTime.split(':')
	    if (akaNow.replace(':', '').toInteger() >= timN.startTime.replace(':', '').toInteger()) continue
	    hasSched = true
	    break;	// quit the for loop on a schedule of first startTime that's in the future.
	}
	//.21end
	//logDebug {"schedule('0 $stm $sth ? * *', schedHandler, [data: ['dKey': $sk]]), hasSched: $hasSched"}
	if (hasSched) { 
		schedule("0 ${stm} ${sth} ? * *", schedHandler, [data: ["dKey":"$sk"]]) 
		logInfo {"$myLabel scheduled today."}
		logDebug {"Scheduled events list for today: ${timings}"}
	}
	else {
		logInfo {"Nothing scheduled for $myLabel today."}
	}
}


/*
-----------------------------------------------------------------------------
   Schedule Helper/Handler functions
-----------------------------------------------------------------------------
*/

def schedHandler(data) {
	unschedule(schedHandler)	// don't repeat this day after day.
	String myLabel = app.label
	if (app.label.contains('<span ')) {
		myLabel = app.label.substring(0, app.label.indexOf('<span '))
	}
	logInfo {"Running $myLabel Schedule."}
	cd = data["dKey"] as String
	duraT = state.dayGroupMerge."$cd".duraTime

	// if the schedule to be run is an 'ot' (overTemp) and today doesn't have an overTemp, then skip
	if(state.dayGroupMerge[cd].ot && !state.overTempToday) {
		logInfo {"No Over Temperature today, skipping."}
		runIn(60, scheduleNext)			// find and then schedule the next startTime for today
		return
	}

	if (duraT == 0) {
		logInfo {"Duration of 0, skipping."}
		runIn(60, scheduleNext)			// find and then schedule the next startTime for today
		return	
	}

	if (rainEnableDevice && rainEnableDevice != "0" && state.rainDeviceOutdoor[rainEnableDevice]?.value?.toLowerCase() == "wet") { 
		logWarn {"Rain Hold - schedule skipped for $myLabel Today."}
		runIn(60, scheduleNext)			// find and then schedule the next startTime for today
		return
	}	

	valve2start = state.valves.findAll { it.value.dayGroup.contains(cd) }.keySet()
	if (!valve2start) {
		logInfo {"No Switch in Day Group."}
		runIn(60, scheduleNext)			// find and then schedule the next startTime for today
		return
	}
	logDebug {"schedHandler: $cd, $state.dayGroupMerge, valve2start: $valve2start"} 

	vk = valve2start[0] as String
	if (vk != null) {
		valve2start = valve2start.tail()
	}

   	currentValve = settings.valves?.find{it.id == "$vk"}
	// some valves need turning on for their duration.
	currentValve?.on()
	logInfo {"Valve switch ${currentValve?.label ?: currentValve?.name} on."}
	state.inCycle = true
	atomicState.cycleStart = now()
	updateMyLabel(3)

	duraT = state.dayGroupMerge."$cd".duraTime
	currentMonth = new Date().format("M") 	// Get the current month as a number (1-12)
	currentMonthPercentage = state.month2month ? state.month2month[currentMonth].toDouble() / 100 : 1  // Lookup the percent in month2month or 1 
	dura = 60 * duraT * currentMonthPercentage		// duraTime is in minutes, runIn is in seconds
	duraSeconds = Math.max(dura.toInteger(), 20) // Ensure minimum valve timing of 20 seconds
	logDebug {"runIn($duraSeconds, scheduleDurationHandler, [vKey: $vk, dS: $duraSeconds, dV: $valve2start, dKey: $cd])"}

 	runIn(duraSeconds, scheduleDurationHandler, [data: [vKey: "$vk", dS: "$duraSeconds", dV: "$valve2start", dKey: "$cd"]]) 
}


def scheduleDurationHandler(data) {
	unschedule(scheduleDurationHandler)	// don't repeat this day after day.
	String vk = data.vKey as String
	String cd = data.dKey as String
	duraSeconds = data.dS.toInteger()
	valve2start = data.dV as String
	logDebug {"schedDurHandler: valveStop: $vk, in Duration: $duraSeconds, next: $valve2start"}

   	currentValve = settings.valves?.find{it.id == "$vk"}
	// stop the valve and start the next, if any.
	currentValve?.off()
	logInfo {"Valve switch ${currentValve?.label ?: currentValve?.name} off."}

	// add an interstitial delay to allow the valves to close and recover before opening next.
	pauseExecution(20000)	// 20 seconds between off and the next on
	
	//  Abort watering if rainhold is detected 
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
	//.20
	} else {
        // Look for cascading groups
        Calendar calendar = Calendar.getInstance();
        def cronDay = calendar.get(Calendar.DAY_OF_WEEK);
        Map aWeek = [1:'7', 2:'1', 3:'2', 4:'3', 5:'4', 6:'5', 7:'6']
        def todayIndex = aWeek[cronDay]

        def cascadedGroup = state.dayGroupMerge.find { k, v ->
            v[todayIndex] == true && v.startTime == "after_${cd}"
        }

        if (cascadedGroup) {
            logInfo {"Day Group $cd complete. Cascading to dependent Day Group ${cascadedGroup.key}."}
            runIn(20, schedHandler, [data: ["dKey": cascadedGroup.key]])
        } else {
            state.inCycle = false
            atomicState.cycleEnd = now()
            runIn(30, scheduleNext)         // find and then schedule the next startTime for today
            updateMyLabel(4)
        }
	}
	//.20end
}

//.20
def buildTimings(cronDayOf) {
	Map aWeek = [1:'7', 2:'1', 3:'2', 4:'3', 5:'4', 6:'5', 7:'6']
	// cronDayOf week is 1-7 where 1 = sunday and 7 = saturday. BUT this app uses 1 as Monday, sunday is 7
	def result = state.dayGroupMerge.findAll { key, value -> value[aWeek[cronDayOf]] == true }.keySet()
	def results = result.collect { key -> [key: key, duraTime: state.dayGroupMerge[key]?.duraTime, startTime: state.dayGroupMerge[key]?.startTime]}.findAll { it.startTime != null }.sort { it.startTime.startsWith("after_") ? "99:99" : it.startTime } // Sort by startTime
	// [[key:2, duraTime:5.0, startTime:06:00]]
}
//.20end

void updateMyLabel(num) {
	String baseLabel = app.label?.with { 
		def flag = '<span '
		contains(flag) ? substring(0, indexOf(flag)) : it
	} ?: ""

	String status = ""
	if (settings.schEnable != true) {
		status = '<span style="color:Crimson"> (inactive)</span>'
	} else if (atomicState.isPaused) {
		status = '<span style="color:Crimson"> (paused)</span>'
	} else if (state.inCycle) {
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
	def yesterday = new Date(today.time - 1 * 24 * 60 * 60 * 1000) // Subtract 1 day
	def tomorrow = new Date(today.time + 1 * 24 * 60 * 60 * 1000) // Add 1 day

	String myDate = ''
	boolean showTime = true

	if (target.clearTime() == today) {
	    myDate = 'today'
	} else if (target.clearTime() == yesterday) {
	    myDate = 'yesterday'
	} else if (target.clearTime() == tomorrow) {
	    myDate = 'tomorrow'
	} else if (target.format('yyyy-MM-dd') == '2035-01-01') { // "Infinity" case
	    myDate = 'a long time from now'
	    showTime = false
	} else {
	    myDate = "on ${target.format('MM-dd')}"
	}

	target = new Date(eventDate)
	String myTime = showTime ? target.format('h:mma').toLowerCase() : ''
	return myTime ? "${myDate} at ${myTime}" : myDate
}


def logsOff() {
	logWarn {"debug logging Disabled..."}
	app?.updateSetting("debugEnable",[value:"false",type:"bool"])
}


def sectFormat(type, myText=""){ 
	if(type == "line") return "<hr style='background-color:#1A77C9; height: 1px; border: 0;'>"
	if(type == "title") return "<h2 style='color:#1A77C9;font-weight: bold'>${myText}</h2>"
	if(type == "subTitle") return "<p style='color:#1A77C9;font-weight: bold; font-size: 1.4em;'>${myText}</p>"
}


def displayHeader() {
	section (sectFormat("title", "Sprinkler Switch Timetable")) {
		paragraph "<div style='color:#1A77C9;text-align:right;font-weight:small;font-size:9px;'>Developed by: C Steele, Matt Hammond<br/>Current Version: ${version()} -  ${thisCopyright}</div>"
		paragraph "\n<hr style='background-color:#1A77C9; height: 1px; border: 0;'></hr>"
	}
}

//.20
private Integer timeToSeconds(String timeStr) {
	if (!timeStr || timeStr.startsWith("after_")) return null
	def parts = timeStr.split(':')
	int h = parts[0].toInteger()
	int m = parts[1].toInteger()
	return (h * 3600) + (m * 60)
}
//.20


String menuHeader(titleText){"<div style=\"width:102%;background-color:#696969;color:white;padding:4px;font-weight: bold;box-shadow: 1px 2px 2px #bababa;margin-left: -10px\">${titleText}</div>"}
def getThisCopyright(){"&copy; 2023 C Steele"}
