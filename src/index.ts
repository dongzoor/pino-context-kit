import { AsyncLocalStorage } from 'node:async_hooks';
import { pino, type DestinationStream, type Logger, type LoggerOptions as PinoOptions } from 'pino';

export type LogContext = Readonly<Record<string, unknown>>;
export type LoggerOptions<CustomLevels extends string = never, UseOnlyCustomLevels extends boolean = boolean> =
  Omit<PinoOptions<CustomLevels, UseOnlyCustomLevels>, 'mixin' | 'mixinMergeStrategy'>;

const contextStorage = new AsyncLocalStorage<LogContext>();
const emptyContext: LogContext = Object.freeze({});

/** Shallowly snapshots and inherits context; restores the parent on exit. */
export function withLogContext<T>(context: LogContext, callback: () => T): T {
  return contextStorage.run({ ...contextStorage.getStore(), ...context }, callback);
}

/** Creates a native Pino logger; only the context mixin is managed here. */
export function createLogger<CustomLevels extends string = never, UseOnlyCustomLevels extends boolean = boolean>(
  options: LoggerOptions<CustomLevels, UseOnlyCustomLevels> = {},
  destination?: DestinationStream,
): Logger<CustomLevels, UseOnlyCustomLevels> {
  return pino<CustomLevels, UseOnlyCustomLevels>({
    ...options,
    mixin: () => contextStorage.getStore() ?? emptyContext,
    // Pino's default merge mutates its mixin object. Keep scope values authoritative
    // without mutating the context or caller-owned log fields.
    mixinMergeStrategy: (fields, context) => context === emptyContext
      ? fields
      : { ...fields, ...context },
  }, destination);
}
