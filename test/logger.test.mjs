import assert from 'node:assert/strict';
import { setImmediate as nextTurn } from 'node:timers/promises';
import test from 'node:test';
import { createLogger, withLogContext } from '../src/index.ts';

function capture() {
  const records = [];
  const logger = createLogger({}, {
    write(line) { records.push(JSON.parse(line)); },
  });
  return { logger, records };
}

test('overlapping requests keep authoritative IDs without contaminating caller fields or later logs', async () => {
  const { logger, records } = capture();
  const started = Promise.withResolvers();
  const release = Promise.withResolvers();
  const fields = Object.freeze({ request_id: 'incorrect', detail: 'only-once' });

  const first = withLogContext({ request_id: 'request-a' }, async () => {
    logger.info(fields, 'a-start');
    started.resolve();
    await release.promise;
    logger.child({ component: 'client' }).info('a-end');
  });
  await started.promise;
  await withLogContext({ request_id: 'request-b' }, async () => {
    await nextTurn();
    logger.info('b');
  });
  release.resolve();
  await first;
  logger.info('outside');

  assert.deepEqual(records.map(({ msg, request_id }) => [msg, request_id]), [
    ['a-start', 'request-a'], ['b', 'request-b'], ['a-end', 'request-a'], ['outside', undefined],
  ]);
  assert.equal(records[2].detail, undefined);
  assert.equal(records[2].component, 'client');
  assert.equal(fields.request_id, 'incorrect');
});

test('nested scopes restore the parent after rejection and snapshot caller-owned context', async () => {
  const { logger, records } = capture();
  const originalError = new Error('job failed');
  const context = { request_id: 'request-a' };

  await withLogContext(context, async () => {
    context.request_id = 'changed-after-entry';
    await assert.rejects(withLogContext({ job_id: 'job-1' }, async () => {
      await nextTurn();
      logger.warn('nested');
      throw originalError;
    }), (error) => error === originalError);
    logger.info('parent');
  });
  assert.throws(() => withLogContext({ job_id: 'sync-job' }, () => {
    throw originalError;
  }), (error) => error === originalError);
  logger.info('outside');

  assert.deepEqual(records.map(({ request_id, job_id }) => [request_id, job_id]), [
    ['request-a', 'job-1'], ['request-a', undefined], [undefined, undefined],
  ]);
});
