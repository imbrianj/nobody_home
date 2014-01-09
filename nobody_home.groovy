/**
 *  Nobody Home
 *
 *  Author: brian@bevey.org
 *  Date: 9/10/13
 *
 *  Monitors a set of presence detectors and triggers a mode change when everyone has left.
 *  When everyone has left, sets mode to a new defined mode.
 *  When at least one person returns home, set the mode back to a new defined mode.
 *  When someone is home - or upon entering the home, their mode may change dependent on sunrise / sunset.
 */

preferences {
  section("When all of these people leave home") {
    input "people", "capability.presenceSensor", multiple: true
  }

  section("Change to this mode to...") {
    input "newAwayMode",    "mode", title: "Everyone is away"
    input "newSunsetMode",  "mode", title: "At least one person home and nightfall"
    input "newSunriseMode", "mode", title: "At least one person home and sunrise"
  }

  section("False alarm threshold (defaults to 10 min)") {
    input "falseAlarmThreshold", "decimal", title: "Number of minutes", required: false
  }

  section("Notifications") {
    input "sendPushMessage", "enum", title: "Send a push notification?", metadata:[values:["Yes","No"]], required:false
  }
}

def installed() {
  init()
}

def updated() {
  unsubscribe()
  init()
}

def init() {
  subscribe(people, "presence", presence)

  checkSun();
}

def checkSun() {
  def sunInfo = getSunriseAndSunset()
  def current = now()

  if(sunInfo.sunrise.time > current ||
     sunInfo.sunset.time  < current) {
    state.sunMode = newSunsetMode
  }

  else {
    state.sunMode = newSunriseMode
  }

  log.info("Sunset: ${sunInfo.sunset.time}")
  log.info("Sunrise: ${sunInfo.sunrise.time}")
  log.info("Current: ${current}")
  log.info("sunMode: ${state.sunMode}")

  if(current < sunInfo.sunrise.time) {
    runIn(((sunInfo.sunrise.time - current) / 1000).toInteger(), setSunrise)
  }

  if(current < sunInfo.sunset.time) {
    runIn(((sunInfo.sunset.time - current) / 1000).toInteger(), setSunset)
  }

  schedule(timeTodayAfter(new Date(), "01:00"), checkSun)
}

def setSunrise() {
  changeSunMode(newSunriseMode);
}

def setSunset() {
  changeSunMode(newSunsetMode);
}

def changeSunMode(newMode) {
  state.sunMode = newMode

  if(everyoneIsAway() && (location.mode == newAwayMode)) {
    log.debug("Mode is away, not evaluating")
  }

  else if(location.mode != newMode) {
    def message = "${app.label} changed your mode to '${newMode}'"
    send(message)
    setLocationMode(newMode)
  }

  else {
    log.debug("Mode is the same, not evaluating")
  }
}

def presence(evt) {
  if(evt.value == "not present") {
    log.debug("Checking if everyone is away")

    if(everyoneIsAway()) {
      log.info("Starting ${newAwayMode} sequence")
      def delay = (falseAlarmThreshold != null && falseAlarmThreshold != "") ? falseAlarmThreshold * 60 : 10 * 60 
      runIn(delay, "setAway")
    }
  }

  else {
    if(location.mode != state.sunMode) {
      log.debug("Checking if anyone is home")

      if(anyoneIsHome()) {
        log.info("Starting ${state.sunMode} sequence")

        changeSunMode(state.sunMode)
      }
    }

    else {
      log.debug("Mode is the same, not evaluating")
    }
  }
}

def setAway() {
  if(everyoneIsAway()) {
    if(location.mode != newAwayMode) {
      def message = "${app.label} changed your mode to '${newAwayMode}' because everyone left home"
      log.info(message)
      send(message)
      setLocationMode(newAwayMode)
    }

    else {
      log.debug("Mode is the same, not evaluating")
    }
  }

  else {
    log.info("Somebody returned home before we set to '${newAwayMode}'")
  }
}

private everyoneIsAway() {
  def result = true

  if(people.findAll { it?.currentPresence == "present" }) {
    result = false
  }

  log.debug("everyoneIsAway: ${result}")

  return result
}

private anyoneIsHome() {
  def result = false

  if(people.findAll { it?.currentPresence == "present" }) {
    result = true
  }

  log.debug("anyoneIsHome: ${result}")

  return result
}

private send(msg) {
  if(sendPushMessage != "No") {
    log.debug("Sending push message")
    sendPush(msg)
  }

  log.debug(msg)
}
