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
        fixture.aliasWrite,
        fixture.reintroducedField,
        fixture.readBeforeOverwrite,
        fixture.otherParentOverwrite,
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
        fixture.aliasWriteOther,
        fixture.reboundBase,
        fixture.capturedRebinding,
        fixture.parentFieldOverwrite,
      ]) {
        expect(invoke(input), 'constant');
      }
      expect(fixture.guardedAlias(input, true), input);
      expect(fixture.guardedAlias(input, false), 'constant');
      for (final flag in [false, true]) {
        expect(
          fixture.conditionalOverwrite(input, flag),
          flag ? 'constant' : input,
        );
        expect(fixture.bothBranchesOverwrite(input, flag), 'constant');
        expect(
          fixture.throwingOverwrite(input, flag),
          flag ? input : 'constant',
        );
      }
      for (var count = 0; count <= 2; count++) {
        expect(
          fixture.optionalLoopOverwrite(input, count),
          count == 0 ? input : 'constant',
        );
        expect(fixture.mandatoryLoopOverwrite(input, count), 'constant');
      }
    }
  });
}
