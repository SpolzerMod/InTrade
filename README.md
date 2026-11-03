# InTrade

![Paper](https://img.shields.io/badge/Paper-1.21.1--26.3-blue)
![Java](https://img.shields.io/badge/Java-21-orange)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

InTrade is a player-to-player trading plugin for Paper servers. Two players put items, money, experience levels and points into one window, both confirm, and the exchange happens at once.

It is:

* **safe** - any change resets both confirmations and locks the Ready button for a moment. Every change of an offer is written to the database before the items move, so a crash can neither lose nor duplicate them. Blocked items are also found inside shulker boxes and bundles.
* **complete** - Vault money, experience levels, PlayerPoints and currencies of other plugins next to 16 item slots per side. Amounts can be typed as `1500`, `2.5k` or `1m`. An optional tax per currency is taken from what the receiver gets.
* **transparent** - shulker boxes and bundles in the partner's offer can be opened before confirming, and every trade is kept in the history.
* **vanilla** - no client mod or resource pack. Amounts are typed in a dialog, or in an anvil before 1.21.7. Bedrock players get native forms through Floodgate.
* **multilingual** - each player sees their client language. English and Russian are included.
* **network ready** - SQLite out of the box, or one MySQL / MariaDB database shared by several servers.

## Requirements

* Paper 1.21.1 - 26.3 (one jar for all versions)
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

## Tax

`currencies.<id>.tax-percent` in `config.yml` (0 to 100 with up to 3 decimal places, default 0) applies to built-in currencies and currencies of other plugins. The receiver of an amount gets the amount minus the tax, the tax is removed from the economy. The tax is rounded up to the smallest unit of the currency, so splitting a payment into many small trades does not avoid it. The trade window shows what the partner receives and what you receive after tax. Trade history and events keep the full amounts. A reload resets the confirmations of open trades, so nobody completes a trade at a rate they did not see.

## API

Take `api-<version>.jar` from [Releases](../../releases) and add InTrade to `depend` or `softdepend` in your `plugin.yml`.

```kotlin
dependencies {
    compileOnly(files("libs/api-2.2.0.jar"))
}
```

```java
InTrade.get().tradeCount(player.getUniqueId())
        .thenAccept(count -> getLogger().info(player.getName() + " has " + count + " trades"));
```

Events: `TradeRequestEvent` and `TradeStartEvent` (cancellable), `TradeCompleteEvent`, `TradeCancelEvent`. Currency amounts are `BigDecimal`. Futures complete on a database thread, so switch to the main thread before calling the Bukkit API.

### Currencies of other plugins

Implement `me.spolzer.intrade.api.currency.TradeCurrency` and register it as a service. Several plugins can register currencies; InTrade picks them up when it starts, on `/trade reload` and whenever a provider is registered or unregistered.

```java
getServer().getServicesManager().register(TradeCurrency.class, new TokenCurrency(), this, ServicePriority.Normal);
```

```yaml
currencies:
  tokens:
    enabled: true   # currencies of other plugins are off until enabled
```

* `id()` is lower case `[a-z0-9_-]{1,32}`. It is the config key `currencies.<id>`, the permission `intrade.currency.<id>` (granted by `intrade.player`) and the language key `currency.<id>` (the id is shown if the key is missing). `money`, `experience` and `playerpoints` are reserved for the built-in currencies.
* `icon()` must be an item and `scale()` between 0 and 18. Both are read once when the currency is loaded; a currency with an invalid id, icon or scale is ignored with a warning.
* `balance(player)` is called while the trade window is shown: it must be cheap and must never block. If `balance` or `format` throws or returns null, the trade that called it is cancelled, the items go back to their owners and the error is logged with the currency, its plugin and the players.
* `settle(trade, payments)` applies all payments of one completed trade in this currency, all or none: `true` everything applied, `false` nothing applied (for example a balance changed), an exception: outcome unknown. Each `Payment(from, to, amount, tax)` moves `amount` from `from` and gives `amount - tax` to `to`. The trade id is unique per completion attempt, use it as an idempotency key.
* `revert(trade)` undoes a successful `settle` when a currency settled later fails. It must be idempotent; an exception means it was not undone.

Both run on the main thread while the trade completes, like the transfers of the built-in currencies. They may block briefly (a local transaction) but must never wait for a network call.

When both players confirm, InTrade checks the balances and inventory space, settles the external currencies one after the other in the order of the config, then moves the built-in currencies. On a failure the steps done so far are undone in reverse order and the trade stays open. If an outcome is unknown or an undo fails, the trade is cancelled, the items go back to their owners and the trade id is logged at `SEVERE` with the currencies to check. As with the built-in currencies, a server stop between the currency settlement and the item swap is not covered.

The trade window has room for three currencies, built-in ones first, then the others in the order of the config. Only those are used. When a currency is disabled, unregistered, replaced by a new provider or pushed out of the window, its amounts are removed from open trades and both players confirm again. An amount that a player is still typing for it is discarded.

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

The plugin jar is in `plugin/build/libs`, the API jar in `api/build/libs`. `build` also runs the unit tests (JUnit 6, MockBukkit, SQLite).

The code is compiled against Paper API 1.21.1 with Java 21 bytecode. The dialog input lives in its own source set (`plugin/src/dialog`), compiled against 1.21.7 and loaded only on servers that have dialogs. The `verifyLatestApi` task, which runs as part of `build`, compiles the same sources against Paper API 26.3 to catch removed methods; this is why JDK 25 is needed.

## Project layout

* **api** - the public API: the `InTrade` service, `TradeOffer`, `TraderStats`, `TradeCurrency` and events. Shaded into the plugin jar.
* **plugin** - the implementation.
  * `trade` - requests, trade sessions and the trade window
  * `currency` - Vault, experience and PlayerPoints (`BuiltInCurrency`) and currencies of other plugins (`ExternalCurrency`) behind one `Currency` interface
  * `storage` - SQLite / MySQL through HikariCP. Writes go through one thread to keep their order, results return to the main thread through `MainThread`. `ServerLock` keeps two running servers from sharing one `server-id`
  * `menu`, `input`, `text`, `config`, `hook` - history menus, amount input, messages, settings, PlaceholderAPI

HikariCP and the MySQL driver are not shaded: they are listed under `libraries` in `plugin.yml` and downloaded by Paper.

## License

InTrade is licensed under the MIT license. See [`LICENSE`](LICENSE) for more info.
