import 'package:test/test.dart';

import '../../src/test/resources/semantics/static_exception_storage.dart'
    as fixture;
import '../../src/test/resources/semantics/static_storage.dart' as storage;

void main() {
  test(
    'exceptional memory survives until a selected store or cleanup replaces it',
    () {
      for (final input in ['', 'input', 'second']) {
        for (final fail in [false, true]) {
          expect(
            fixture.swallowedResult(input, fail),
            fail ? 'handled' : input,
          );
          expect(
            fixture.joinedCaught(input, fail),
            fail ? input : 'normal result',
          );
          expect(
            fixture.copiedCaught(input, fail),
            fail ? input : 'normal result',
          );
          expect(fixture.copiedBoth(input, fail), fail ? input : 'normal copy');
          expect(
            fixture.localCopyCaught(input, fail),
            fail ? input : 'normal result',
          );
          expect(
            fixture.helperCopyCaught(input, fail),
            fail ? input : 'normal result',
          );
          if (fail) {
            expect(() => fixture.normalBranch(input, fail), throwsStateError);
            expect(() => fixture.joinedNormal(input, fail), throwsStateError);
            expect(() => fixture.copiedNormal(input, fail), throwsStateError);
            expect(
              () => fixture.localCopyNormal(input, fail),
              throwsStateError,
            );
            expect(
              () => fixture.helperCopyNormal(input, fail),
              throwsStateError,
            );
            expect(storage.read(), input);
          } else {
            expect(fixture.normalBranch(input, fail), 'normal');
            expect(fixture.joinedNormal(input, fail), 'normal');
            expect(fixture.copiedNormal(input, fail), 'normal copy');
            expect(fixture.localCopyNormal(input, fail), 'normal local');
            expect(fixture.helperCopyNormal(input, fail), 'normal copy');
          }
          expect(
            fixture.caughtBranch(input, fail),
            fail ? input : 'normal result',
          );
          expect(
            fixture.conditionalCleanup(input, fail),
            fail ? 'cleanup' : input,
          );
        }
        expect(fixture.nestedThrow(input), input);
        expect(fixture.caughtOverwrite(input), 'constant');
        expect(fixture.caughtIndependent(input), 'constant');
        expect(fixture.copiedIndependent(input), 'constant');
        expect(fixture.cleanupKilled(input), 'cleanup');
        expect(fixture.cleanupPreserved(input), input);
        expect(storage.Shared.other, 'unrelated cleanup');
        expect(fixture.suppressedResult(input), input);
        expect(fixture.cleanupThrows(input), input);
        expect(fixture.replacedException(input), 'replacement');
        expect(fixture.nestedCleanupKilled(input), 'outer');
        expect(fixture.rethrown(input), input);
      }
    },
  );
}
