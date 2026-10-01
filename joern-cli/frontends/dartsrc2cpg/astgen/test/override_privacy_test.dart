import 'package:test/test.dart';

import '../../src/test/resources/semantics/override_privacy.dart' as fixture;
import '../../src/test/resources/semantics/override_privacy_foreign.dart'
    as foreign;

void main() {
  test(
    'private libraries and receiver static types constrain override targets',
    () {
      for (final input in ['', 'input']) {
        for (final ignored in ['', 'ignored']) {
          final value = foreign.Foreign();
          expect(value.invoke(input, ignored), input);
          expect(value.own(input, ignored), ignored);
          for (final receiver in [fixture.Branch(), fixture.SubBranch()]) {
            expect(fixture.narrow(receiver, input, ignored), input);
            expect(fixture.boundNarrow(receiver, input, ignored), input);
          }
          expect(fixture.Sibling().echo(input, ignored), ignored);
        }
      }
    },
  );
}
