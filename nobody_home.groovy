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

  section("Zip code (for sunrise/sunset)") {
    input "zip", "decimal", required: false
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
  def zip            = settings.zip as String
  def locale         = getWeatherFeature("geolookup", zip)
  def timezone       = TimeZone.getTimeZone(locale.location.tz_long)
  def weather        = getWeatherFeature("astronomy", zip)

  def sunriseCompare = (weather.moon_phase.sunrise.hour      + weather.moon_phase.sunrise.minute).toInteger()
  def sunsetCompare  = (weather.moon_phase.sunset.hour       + weather.moon_phase.sunset.minute).toInteger()
  def currentCompare = (weather.moon_phase.current_time.hour + weather.moon_phase.current_time.minute).toInteger()

  def sunrise        = weather.moon_phase.sunrise.hour       + ":" + weather.moon_phase.sunrise.minute
  def sunset         = weather.moon_phase.sunset.hour        + ":" + weather.moon_phase.sunset.minute
  def current        = weather.moon_phase.current_time.hour  + ":" + weather.moon_phase.current_time.minute

  if(sunriseCompare > currentCompare ||
     sunsetCompare  < currentCompare) {
    state.sunMode = newSunsetMode
  }

  else {
    state.sunMode = newSunriseMode
  }

  log.info("Sunset: ${sunset}")
  log.info("Sunrise: ${sunrise}")
  log.info("Current: ${current}")
  log.info("sunMode: ${state.sunMode}")

  schedule(timeToday(sunrise, timezone), setSunrise)
  schedule(timeToday(sunset,  timezone), setSunset)
  schedule(timeTodayAfter(new Date(), "01:00", timezone), checkSun)
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
