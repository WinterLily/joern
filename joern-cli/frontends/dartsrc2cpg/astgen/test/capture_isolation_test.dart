import 'package:test/test.dart';

import '../../src/test/resources/semantics/capture_isolation.dart' as fixture;

void main() {
  test('captures preserve binding identity and replacement values', () {
    for (final input in ['', 'input']) {
      for (final invoke in [
        fixture.capturedValue,
        fixture.nestedCapture,
        fixture.localCapture,
      ]) {
        expect(invoke(input), input);
      }
      for (final invoke in [
        fixture.unrelatedLocal,
        fixture.overwrittenCapture,
        fixture.shadowedCapture,
        fixture.localOverwrite,
      ]) {
        expect(invoke(input), 'constant');
      }
      for (final replace in [false, true]) {
        expect(
          fixture.conditionalCapture(input, replace),
          replace ? 'constant' : input,
        );
      }
    }
  });
}
