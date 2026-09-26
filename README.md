# InTrade

![Paper](https://img.shields.io/badge/Paper-1.21.7--26.3-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

InTrade is a player-to-player trading plugin for Paper servers. Two players put items, money, experience levels and points into one window, both confirm, and the exchange happens at once.

It is:

* **safe** - any change resets both confirmations and locks the Ready button for a moment. Items of an open trade are kept in the database and returned after a crash.
* **complete** - Vault money, experience levels and PlayerPoints next to 16 item slots per side. Amounts can be typed as `1500`, `2.5k` or `1m`.
* **transparent** - shulker boxes and bundles in the partner's offer can be opened before confirming, and every trade is kept in the history.
* **vanilla** - no client mod or resource pack. Bedrock players get native forms through Floodgate.
* **multilingual** - each player sees their client language. English and Russian are included.
* **network ready** - SQLite out of the box, or one MySQL / MariaDB database shared by several servers.

## Requirements

* Paper 1.21.7 - 26.3 (one jar for all versions)
* Java 21 or newer

Optional: Vault with an economy plugin, PlayerPoints, Floodgate, PlaceholderAPI.

## Installation

Download the jar from [Releases](../../releases), put it into `plugins` and restart the server. All options are described in [`config.yml`](plugin/src/main/resources/config.yml), messages are in `lang/<code>.yml`.

## Commands

| Command | Permission |
| --- | --- |
| `/trade <player>` | `intrade.use` |
| `/trade accept [player]`, `/trade deny [player]` | `intrade.use` |
| `/trade claim` | `intrade.use` |
| `/trade toggle` | `intrade.toggle` |
| `/trade history [player]` | `intrade.history`, `intrade.history.others` |
| `/trade reload` | `intrade.reload` |

Player permissions are grouped under `intrade.player` (default: everyone), staff permissions under `intrade.admin` (default: op). The full list is in [`plugin.yml`](plugin/src/main/resources/plugin.yml).

PlaceholderAPI: `%intrade_trades%`, `%intrade_trading%`, `%intrade_accepting%`, `%intrade_top_<1-10>_name%`, `%intrade_top_<1-10>_trades%`.

## API

Take `api-<version>.jar` from [Releases](../../releases) and add InTrade to `depend` or `softdepend` in your `plugin.yml`.

```kotlin
dependencies {
    compileOnly(files("libs/api-2.0.0.jar"))
}
```

```java
InTrade.get().tradeCount(player.getUniqueId())
        .thenAccept(count -> getLogger().info(player.getName() + " has " + count + " trades"));
```

Events: `TradeRequestEvent` and `TradeStartEvent` (cancellable), `TradeCompleteEvent`, `TradeCancelEvent`. Currency amounts are `BigDecimal`. Futures complete on a database thread, so switch to the main thread before calling the Bukkit API.

## Building

InTrade uses Gradle.

#### Requirements

* JDK 25
* Git

#### Compiling from source

```sh
git clone https://github.com/SpolzerMod/InTrade.git
cd InTrade/
./gradlew build
```

The plugin jar is in `plugin/build/libs`, the API jar in `api/build/libs`.

The code is compiled against Paper API 1.21.7 with Java 21 bytecode. The `verifyLatestApi` task, which runs as part of `build`, compiles the same sources against Paper API 26.3 to catch removed methods; this is why JDK 25 is needed.

## Project layout

* **api** - the public API: the `InTrade` service, `TradeOffer`, `TraderStats` and events. Shaded into the plugin jar.
* **plugin** - the implementation.
  * `trade` - requests, trade sessions and the trade window
  * `currency` - Vault, experience and PlayerPoints behind one `Currency` interface
  * `storage` - SQLite / MySQL through HikariCP. Writes go through one thread to keep their order, results return to the main thread through `MainThread`
  * `menu`, `input`, `text`, `config`, `hook` - history menus, amount input, messages, settings, PlaceholderAPI

HikariCP and the MySQL driver are not shaded: they are listed under `libraries` in `plugin.yml` and downloaded by Paper.

## License

InTrade is licensed under the MIT license. See [`LICENSE`](LICENSE) for more info.
