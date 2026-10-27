# DialogCoinflips

A Vault-powered coinflip plugin for Paper, with native dialogs for Java clients and chest inventory menus for Bedrock clients detected through Floodgate.

## Requirements

- Paper 26.1.x–26.3 (build against Paper 26.2 API)
- Java 25
- Vault and a Vault-compatible economy provider
- Geyser + Floodgate for Bedrock client detection and chest GUI routing

## Commands

- `/cf` — browse open coinflips
- `/cf create <amount>` — open a Confirm/Cancel screen and create a wager after confirmation
- `/cf cancel` — cancel your open coinflip and refund its wager

Aliases: `/coinflip`, `/coinflips`.

## Wager flow

The creator's wager is withdrawn after confirmation. A challenger must have the same balance and has their wager withdrawn when joining. The roulette resolves between the two players and pays the winner the combined pot. Open wagers are refunded when the plugin disables. Wagers are not persisted across server restarts.

## Configuration

`config.yml` controls `max-wager` and `roulette-ticks`.

## Build

Run `mvn clean package` with Java 25 installed. Put the resulting `target/DialogCoinflips-1.0.0.jar` in the server's `plugins` folder.
