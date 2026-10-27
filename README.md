# DialogCoinflips

A Vault-powered coinflip plugin for Paper with native Minecraft dialogs for Java players and chest-GUI menus for Bedrock players detected with Floodgate.

## Features

- `/cf` opens the list of open coinflips.
- `/cf create <amount>` opens a Java native Confirm/Cancel dialog or a Bedrock Confirm/Cancel chest GUI.
- Each listed coinflip shows the creator's Minecraft player head and wager amount.
- Clicking a listed player head starts a roulette-style animation between the creator and challenger.
- The winner receives both wagers.
- `/cf cancel` cancels your open coinflip and refunds your wager.
- Supports wager shorthand such as `1k`, `2.5m`, and `1b`.
- Open wagers are refunded when the plugin shuts down.
- Maximum wager and roulette duration are configurable.

## Requirements

- Paper 26.1.x–26.3 (compiled against Paper 26.1.2 API)
- Java 25
- Vault and a Vault-compatible economy provider
- Geyser + Floodgate for Bedrock detection and Bedrock chest GUIs

## Commands

- `/cf` — browse open coinflips
- `/cf create <amount>` — open Confirm/Cancel creation screen
- `/cf cancel` — cancel and refund your open coinflip

Aliases: `/coinflip`, `/coinflips`.

## Wager flow

When a creator confirms, their wager is withdrawn and held as an open coinflip. A challenger must have at least the same balance; their wager is withdrawn when joining. The roulette picks one of the two players, and the winner receives the combined pot. Coinflips are in-memory only and do not persist through a server restart; open creator wagers are refunded during plugin disable.

## Configuration

`config.yml`:
- `max-wager`: maximum amount accepted by `/cf create`
- `roulette-ticks`: roulette duration in steps (each step is 2 server ticks)

## Build

Use Java 25 and run:

```sh
mvn clean package
```

Output: `target/DialogCoinflips-1.1.0.jar`.
