# SprinklerSchedulePlus for Hubitat Elevation

SprinklerSchedulePlus is a high-performance, monolithic agronomic irrigation controller designed for the Hubitat Elevation platform. It implements advanced water budgeting via the **ASCE Penman-Monteith Evapotranspiration (ET)** equation alongside real-time soil water capacity tracking. 

By calculating local atmospheric moisture losses down to the square foot, this application dynamically adjusts valve runtimes to match the precise hydration needs of your landscape, preventing overwatering and runoff.

---

## Core Operational Concepts

### 1. Dynamic Soil Water Capacity Ledger
Unlike traditional smart timers that rely on coarse weather predictions, this app operates like a bank ledger for your soil reservoir:
* **Water Loss (Debits):** Every day at 11:55 PM, the math engine compiles 24 hours of local sensor telemetry to solve for total water loss via soil evaporation and plant transpiration (ET).
* **Water Gain (Credits):** Local rainfall is actively tracked using an agronomic **80% Effective Rainfall Coefficient** to account for initial canopy interception and surface runoff. Physical valve runtimes are monitored asynchronously, and actual water application depths are credited straight back to the root zone ledger.

### 2. Actual vs. Theoretical (Ghost) Ledgers
To support advanced verification testing, the application tracks dual ledgers:
* **Actual Ledger:** Tracks real-world turf conditions based on actual physical valve closures.
* **Theoretical "Ghost" Ledger:** Runs in the background to simulate what *would* have happened over time under specific configurations, serving as a zero-risk testing sandbox.

---

## Hardware & Attribute Mapping Requirements

To utilize the **Smart ET Engine**, your Hubitat environment must feature local sensors capable of transmitting the following attributes. *Multi-sensors (e.g., Ecowitt weather stations) can be mapped across multiple fields simultaneously.*

| Sensor Input | Primary Attribute | Alternative Fallback Route |
| :--- | :--- | :--- |
| **Air Temperature** | `temperature` | *Required for thermodynamic constants* |
| **Wind Speed** | `windSpeed` (Averaged) | *Avoid instantaneous gusts to prevent spikes* |
| **Solar Radiation** | `illuminance` / `solarradiation` | *Converted mathematically to absolute Megajoules* |
| **Vapor Pressure Deficit** | `vpd` (Native) | Mapped `humidity` sensor *(Hub will calculate VPD)* |
| **Rain Gauge** | `dailyrainin` (Midnight accumulation) | *Exempt from timeouts; strictly event-driven* |

### Sleepy Device Protocol
For battery-powered Zigbee or Z-Wave sensors that only check in when thresholds physically change, toggle **Sleepy Device Tolerance** in the settings. This applies a 4x multiplier to the hardware heartbeat window (expanding the timeout gate from 3 to 12 hours) to prevent stable daytime conditions from triggering a false offline hardware fault.

---

## Installation & Setup Sequence

### Step 1: Initialize General Configuration
1. Paste the monolithic source code into your Hubitat **Apps Code** section and click **Save**.
2. Navigate to **Apps** -> **Add User App** -> select **SprinklerSchedulePlus**.
3. Provide a custom name and select all physical valve relay switches under **Switch Select**. Click **Done** to initialize the serialization arrays.

### Step 2: Establish the Timetable Matrix
Click on the **Timetable Matrix** subpage to organize your watering schedules into logical **Day Groups**:
1. Click **`+`** to generate a new Day Group.
2. Toggle the weekdays the group is allowed to operate.
3. Set the **Start Trigger**:
   * **Scheduled Time:** An absolute clock execution time (e.g., `04:30`).
   * **After Day Group:** Cascades groups sequentially. For example, setting Group 2 to trigger *After Day Group 1* constructs a continuous zone-by-zone queue that automatically accommodates dynamic ET runtimes.
4. Set the **Duration Limit** (serves as the static run window or the maximum allowable cap for an ET run).
5. Map your physical switches to the group by clicking the purple cell matching the zone.

### Step 3: Align Soil & Site Data
Navigate to **Global Settings & Overrides**:
1. **Fetch Global Elevation:** Automatically contacts the Open-Meteo DEM API using your Hub's native latitude/longitude to calculate local atmospheric pressure adjustments.
2. **Fetch USDA Soil Data:** Connects directly to the federal NRCS Soil Data Access database to map your exact coordinate polygon. It extracts the true measured **Available Water Capacity (AWC)** and automatically catches highly localized anomalies (such as rocky or gravelly profiles that have severe water retention limitations).

### Step 4: Map ET Telemetry Fields
If **Smart ET** is toggled on, open the **Evapotranspiration Config** subpage:
1. Assign your hardware sensors and pick their respective data attributes.
2. Define individual **Zone Crop Coefficients (Kc)** to scale transpirational models to match distinct biological profiles (e.g., tall cool-season turf grass vs. sheltered drip lines or native shrubs).
3. Set the physical **Application Rate (in/hr)** matching your irrigation head layout geometry (e.g., `0.40 in/hr` for rotary rotors).

---

## UI Color & Metric Legend

The **Zone Agronomy Ledger** table on the main dashboard displays real-time health metrics using the following semantic markers:

* **Net Loss:** Displays a rolling decimal estimate of daily moisture change ($ET - Rain$). Displays `--` until the scheduled 11:55 PM calculation block closes the daily accumulators.
* **Act / Theo Deficit:** Displays the precise depth deficit of your soil profile.
  * <span style="color:red; font-weight:bold">Red String</span>: The root zone is in a deficit state and drawing down towards Management Allowable Depletion (MAD). Sprinklers will run on the next open day window.
  * <span style="color:green; font-weight:bold">Green String</span>: The soil moisture profile is perfectly balanced at structural baseline equilibrium.
  * <span style="color:blue; font-weight:bold">Blue String</span>: The soil profile is currently saturated or experiencing a temporary surface-pooling buffer. Runtimes will automatically scale to 0 seconds.

---

## Advanced Options & Overrides

### 1. Passive / Test Mode
When Passive Mode is enabled, the calculation engine runs at full speed, processing live weather metrics, updating the theoretical ledgers, and evaluating run-times. However, **all `.on()` commands to physical relays are strictly bypassed**. Use this mode to baseline and audit the scheduling logic before handing over physical control to the system.

### 2. Granular Rain Hold Bypass
Global holds can be managed via an external rain sensor or soil moisture cutoff probe. If an asset (like a swimming pool fill valve or a completely sheltered greenhouse drip system) must irrigate regardless of regional storm events, toggle the **RainBypass** column option on that specific Day Group row within the matrix layout.