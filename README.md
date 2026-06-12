# Happy Ghast: Automation

Happy Ghast: Automation adds simple station-to-station autopilot for Happy Ghasts.
Place named Ghast Stations, write a route onto an FSD Task, and attach the task to a Happy Ghast to make it fly between stops automatically.

## Features

- Ghast Stations with custom names, docking height, and arrival sounds.
- FSD Tasks that store a station route.
- Automatic Happy Ghast flight between named stations.
- Optional route looping.
- Departure conditions for each stop:
  - wait for a number of seconds
  - wait for passengers
  - wait for redstone on
  - wait for redstone off
- Comparator output from Ghast Stations when a Happy Ghast is docked nearby.
- FSD Task Remover for taking a saved task back from a Happy Ghast.

## Items and Blocks

### Ghast Station

A named stop for Happy Ghast routes.

Right-click a Ghast Station to open its settings.

Set a station name before using it in a route. Station names are what FSD Tasks use to find destinations.

You can also set:

- docking height
- arrival sound
- arrival note

### FSD Task

An item that stores the route a Happy Ghast should follow.

Left-click with an FSD Task to edit its route. Add named Ghast Stations, reorder stops, choose a departure condition for each stop, and save the route.

After saving at least one route command, sneak-right-click a Happy Ghast with the FSD Task to install it. The Happy Ghast will start flying the saved route.

### FSD Task Remover

A tool for removing an installed FSD Task from a Happy Ghast.

Right-click a Happy Ghast with the remover to take its installed FSD Task back. The task keeps the current focused route action.

## Basic Use

1. Place a Ghast Station at each stop.
2. Right-click each Ghast Station and give it a unique name.
3. Hold an FSD Task and left-click to open the route editor.
4. Add the stations in the order the Happy Ghast should visit them.
5. Choose a departure condition for each station.
6. Save the FSD Task.
7. Sneak-right-click a Happy Ghast with the FSD Task to install it.

The Happy Ghast will fly to the selected stations and dock above each Ghast Station. If route looping is enabled, it returns to the first station after the last one.

## Redstone

Ghast Stations can be used in redstone systems.

- A comparator reads 15 when a Happy Ghast is docked near the station.
- Route stops can wait for the station to receive redstone power.
- Route stops can also wait until the station is no longer powered.

This makes it possible to build loading bays, passenger gates, or simple dispatch systems.

## Notes

- A Happy Ghast needs a non-empty FSD Task route before the task can be installed.
- Baby Happy Ghasts cannot use FSD Tasks.
- Station names should be unique in the same dimension.
- If a station is renamed or removed, routes using that station name will no longer find it.
