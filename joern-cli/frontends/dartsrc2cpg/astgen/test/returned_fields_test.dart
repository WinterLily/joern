import 'package:test/test.dart';

import '../../src/test/resources/semantics/returned_fields.dart' as fixture;

void main() {
  test('returned fields retain their own values through calls and aliases', () {
    for (final input in ['', 'input']) {
      for (final invoke in [
        fixture.readValue,
        fixture.nestedValue,
        fixture.aliasValue,
        fixture.copiedField,
        fixture.caughtValue,
      ]) {
        expect(invoke(input), input);
      }
      for (final invoke in [
        fixture.readOther,
        fixture.nestedOther,
        fixture.independentMember,
        fixture.aliasOther,
        fixture.overwrittenField,
        fixture.independentObjects,
        fixture.caughtOther,
      ]) {
        expect(invoke(input), 'constant');
      }
    }
  });
}
