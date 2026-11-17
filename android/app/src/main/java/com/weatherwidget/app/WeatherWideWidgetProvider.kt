package com.weatherwidget.app

/**
 * The 4x2 "Weather Donkey Wide" widget. All behavior — location binding, the
 * per-widget config screen, caching, tap-to-refresh, "Updating…", repainting
 * when the app refreshes — is inherited from [WeatherWidgetProvider]; this
 * class exists only so Android lists it as its own widget with its own size
 * (weather_wide_widget_info.xml). WeatherWidgetProvider.buildViews() notices
 * the widget id belongs to this provider and draws widget_weather_wide.xml.
 */
class WeatherWideWidgetProvider : WeatherWidgetProvider()
