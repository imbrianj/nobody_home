/**
 *  Nobody Home
 *
 *  Author: brian@bevey.org, raychi@gmail.com
 *  Date: 11/21/2015
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing,
 *  software distributed under the License is distributed on an "AS
 *  IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 *  express or implied. See the License for the specific language
 *  governing permissions and limitations under the License.
 */

/**
 *  Monitors a set of presence sensors and trigger appropriate mode
 *  based on configured modes and sunrise/sunset time.
 *
 *  - When everyone has left [Away]
 *  - When at least one person is home during the day [Home]
 *  - When at least one person is home during the night [Night]
 */

// ********** App related functions **********

// The definition provides metadata about the App to SmartThings.
definition (
    name:        "AutoMode",
    namespace:   "imbrianj",
    author:      "brian@bevey.org",
    description: "Automatically set Away/Home/Night mode based on a set of presence sensors and sunrise/sunset time.",
    category:    "Mode Magic",
    iconUrl:     "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience.png",
    iconX2Url:   "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png",
    iconX3Url:   "https://s3.amazonaws.com/smartapp-icons/Convenience/Cat-Convenience@2x.png"
)

// The preferences defines information the App needs from the user.
preferences {
    section("Presence sensor to monitor") {
        input "people", "capability.presenceSensor", multiple: true
    }

    section("Mode setting") {
        input "newAwayMode",    "mode", title: "Everyone is away"
        input "newSunriseMode", "mode", title: "Someone is home during the day"
        input "newSunsetMode",  "mode", title: "Someone is home at night"
    }

    section("Away threshold [default: 5 min]") {
        input "awayThreshold", "decimal", title: "Number of minutes", required: false
    }

    section("Notifications") {
        input "sendPushMessage", "bool", title: "Push notification", required:false
    }
}

// called when the user installs the App
def installed()
{
    log.debug("installed() @ ${location.name}: ${settings}")
    initialize(true)
}

// called when the user installs the app, or changes the App
// preference
def updated()
{
    log.debug("updated() @ ${location.name}: ${settings}")
    unsubscribe()
    initialize(false)
}

def initialize(isInstall)
{
    // subscribe to all the events we care about
    log.debug("Subscribing to events ...")

    // thing to subscribe, attribute/state we care about, and callback fn
    subscribe(people,   "presence", presenceHandler)
    subscribe(location, "sunrise",  sunriseHandler)
    subscribe(location, "sunset",   sunsetHandler)

    // set the optional parameter values. these are not available
    // directly until the app has initialized (that is,
    // installed/updated has returned). so here we access them through
    // the settings object, as otherwise will get an exception.

    // store information we need in state object so we can get access
    // to it later in our event handlers.

    // calculate the away threshold in seconds. can't use the simpler
    // default falsy value, as value of 0 (no delay) is evaluated to
    // false (not specified), but we want 0 to represent no delay. so
    // we compare against null explicitly to see if the user has set a
    // value or not.
    if (settings.awayThreshold != null) {
        state.awayDelay = settings.awayThreshold * 60
    } else {
        state.awayDelay = 5 * 60
    }
    log.debug("awayThreshold set to " + state.awayDelay + " second(s)")

    // get push notification setting
    if (settings.sendPushMessage != null) {
        state.isPush = settings.sendPushMessage
    } else {
        state.isPush = false  // default slider UI is false
    }
    log.debug("sendPushMessage set to " + state.isPush)

    // on install (not update), figure out what mode we should be in
    // IF someone's home. This value is needed so that when a presence
    // sensor is triggered, we know what mode to set the system to, as
    // the sunrise/sunset event handler may not be triggered yet after
    // a fresh install.
    if (isInstall) {
        // TODO: for now, we simply assume daytime. a better approach
        //       would be to figure out whether current time is day or
        //       night, and set it appropriately. However there
        //       doesn't seem to be a way to query this directly
        //       without a zip code. This will become the correct
        //       value at the next sunrise/sunset event.
        log.debug("No sun info yet, assuming daytime")
        state.modeIfHome = newSunriseMode

        // now set the correct mode for the location. This way, we
        // don't need to wait for the next sun/presence event.

        // we schedule this action to run after the app has fully
        // initialized. This way, the app install is faster and the
        // user customized app name is used in the notification.
        runIn(7, "setInitialMode")
    }
    // On update, we don't change state.modeIfHome. This is so that we
    // preserve the current sun rise/set state we obtained in earlier
    // sunset/sunrise handler. This way the app remains in the correct
    // sun state when the user reconfigures it.
}

def setInitialMode()
{
    changeSunMode(state.modeIfHome)
}

// ********** sunrise/sunset handling **********

// event handler when the sunrise time is reached
def sunriseHandler(evt)
{
    // we store the mode we should be in, IF someone's home
    state.modeIfHome = newSunriseMode

    // change mode if someone's home, otherwise set to away
    changeSunMode(newSunriseMode)
}

// event handler when the sunset time is reached
def sunsetHandler(evt)
{
    // we store the mode we should be in, IF someone's home
    state.modeIfHome = newSunsetMode

    // change mode if someone's home, otherwise set to away
    changeSunMode(newSunsetMode)
}

def changeSunMode(newMode)
{
    // if everyone is away, we need to check and ensure the system is
    // in away mode.
    if (isEveryoneAway()) {
        // this shouldn not happen normally as the mode should already
        // be changed during presenceHandler, but in case this is not
        // done, such as when app is initially installed while away,
        // and system is not in away mode, then we toggle it to away
        // at the sun rise/set event.
        changeMode(newAwayMode, " because no one is home")
    } else {
        // someone is home, we update the mode depending on
        // sunrise/sunset.
        changeMode(newMode)  // no-op if already correct mode
    }
}

// ********** presence handling **********

// event handler when presence sensor changes state
def presenceHandler(evt)
{
    // get the device name that resulted in the change
    def deviceName = evt.device?.displayName

    if (evt.value == "not present") {

        // someone left. if no one's home, set away mode after delay
        log.info("${deviceName} left ${location.name}, checking if everyone is away")
        if (isEveryoneAway()) {
            log.info("Everyone is away, scheduling ${newAwayMode} mode in " +
                     state.awayDelay + 's')
            runIn(state.awayDelay, "setAwayMode")
        } else {
            log.info("Someone is still present, no actions needed")
        }

    } else {

        log.info("${deviceName} arrived at ${location.name}")
        // someone returned home, double check if anyone is home.
        if (isAnyoneHome()) {
            // switch to the mode we should be in based on sunrise/sunset
            changeMode(state.modeIfHome)
        } else {
            // no one home, do nothing for now
            log.warn("${deviceName} arrived, but isAnyoneHome() returned false!")
        }

    }
}

// ********** helper functions **********

// change the system to the new mode, unless its already in that mode.
def changeMode(newMode, reason="")
{
    if (location.mode != newMode) {
        // notification message
        def message = "${app.label} changed ${location.name} to '${newMode}' mode" + reason
        setLocationMode(newMode)
        send(message)  // send message after changing mode
    } else {
        log.debug("${location.name} is already in ${newMode} mode, no actions needed")
    }
}

def setAwayMode()
{
    // timer has elapsed, we should double check to ensure everyone is
    // indeed away before we set away mode, as someone may have
    // arrived during the threshold.
    if (isEveryoneAway()) {
        changeMode(newAwayMode, " because everyone left");
    } else {
        log.info("Someone returned before we set to '${newAwayMode}'")
    }
}

private isEveryoneAway()
{
    def result = true

    if (people.findAll { it?.currentPresence == "present" }) {
        result = false
    }
    // log.debug("isEveryoneAway: ${result}")

    return result
}

private isAnyoneHome()
{
    def result = false

    if (people.findAll { it?.currentPresence == "present" }) {
        result = true
    }
    // log.debug("isAnyoneHome: ${result}")

    return result
}

private send(msg)
{
    if (state.isPush) {
        log.debug("Sending push notification")
        sendPush(msg)
    }
    log.info(msg)
}
