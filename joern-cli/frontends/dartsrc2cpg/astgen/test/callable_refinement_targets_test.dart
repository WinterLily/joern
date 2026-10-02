import 'package:test/test.dart';
import '../../src/test/resources/semantics/callable_refinement_targets.dart'
    as fixture;

void main() {
  test('refined callables preserve inputs and selected function identity', () {
    for (final input in ['first', 'independent']) {
      for (final invoke in [
        fixture.castAlias,
        fixture.assertedAlias,
        fixture.aliasChain,
        fixture.directCast,
        fixture.directAssert,
        fixture.directPattern,
        fixture.castPattern,
        fixture.assertedPattern,
        fixture.declaredPattern,
        fixture.joinedSame,
      ]) {
        fixture.events.clear();
        expect(invoke(input, 'ignored'), input);
        expect(fixture.events, ['pick']);
      }
      for (final invoke in [
        fixture.genericAlias,
        fixture.genericPattern,
        fixture.namedAlias,
        fixture.defaultPattern,
      ]) {
        expect(invoke(input, 'ignored'), input);
      }
      for (final flag in [false, true]) {
        for (final invoke in [
          fixture.conditionalAlias,
          fixture.returnedAlias,
          fixture.mutableAlias,
          fixture.mutablePattern,
        ]) {
          fixture.events.clear();
          expect(invoke(input, 'ignored', flag), flag ? input : 'constant');
          expect(fixture.events, [flag ? 'pick' : 'fixed']);
        }
      }
      expect(fixture.parameterAlias(input, 'ignored', fixture.pick), input);
      expect(
        fixture.parameterAlias(input, 'ignored', fixture.fixed),
        'constant',
      );
      expect(fixture.nestedReturnedAlias(input, 'ignored'), input);
      expect(fixture.joinedPattern(input, 'ignored'), input);
      expect(fixture.joinedMixed(input, 'ignored'), input);
    }
  });

  test('refined bound callables retain one captured receiver and defaults', () {
    for (final input in ['first', 'independent']) {
      fixture.events.clear();
      expect(fixture.boundAlias(input, 'ignored'), input);
      expect(fixture.events, [
        'receiver:bound',
        'ignored',
        'input',
        'select:bound',
      ]);
      fixture.events.clear();
      expect(fixture.boundPattern(input, 'ignored'), input);
      expect(fixture.events, ['receiver:pattern', 'select:pattern']);
      fixture.events.clear();
      expect(fixture.repeatedBound(input, 'ignored'), input);
      expect(fixture.events, [
        'receiver:repeated',
        'select:repeated',
        'select:repeated',
      ]);
      fixture.events.clear();
      expect(fixture.capturedPattern(input, 'replacement'), input);
      expect(fixture.events, ['receiver:$input', 'receiver:replacement']);
    }
  });
  test(
    'refined constructor values check types before arguments and creation',
    () {
      for (final input in ['first', 'independent']) {
        for (final invoke in [
          fixture.constructorCast,
          fixture.constructorAlias,
        ]) {
          fixture.events.clear();
          expect(invoke(input, 'supplied'), input);
          expect(fixture.events, ['create:supplied']);
        }
        fixture.events.clear();
        expect(fixture.constructorAssert(input, 'ignored'), input);
        expect(fixture.events, ['create:fallback']);
        fixture.events.clear();
        expect(
          () => fixture.constructorFailure(input),
          throwsA(isA<TypeError>()),
        );
        expect(fixture.events, isEmpty);
      }
    },
  );
}
