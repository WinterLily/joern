import 'package:test/test.dart';

import '../../src/test/resources/semantics/enum_members.dart' as fixture;

void main() {
  test(
    'enum values preserve declaration order, identity and immutable storage',
    () {
      expect(fixture.all(), [fixture.Simple.first, fixture.Simple.second]);
      expect(identical(fixture.all(), fixture.Simple.values), isTrue);
      expect(fixture.Simple.values.map(fixture.describe), [
        '0:first:Simple.first',
        '1:second:Simple.second',
      ]);
      expect(
        () => fixture.all().add(fixture.Simple.first),
        throwsUnsupportedError,
      );
    },
  );
  test('enhanced enum constructors retain generated ordinals and names', () {
    expect(fixture.Enhanced.values.map((value) => value.index), [0, 1]);
    expect(fixture.Enhanced.values.map((value) => value.name), [
      'first',
      'second',
    ]);
    expect(fixture.Enhanced.values.map((value) => value.payload), [10, 20]);
  });
  test(
    'overrides, explicit extension dispatch and private fields stay distinct',
    () {
      expect(fixture.overridden(fixture.Custom.first), 'custom');
      expect(fixture.Custom.first.original(), 'Custom.first');
      expect(fixture.named(fixture.Shadow.first), 'shadow');
      expect(fixture.originalName(fixture.Shadow.first), 'first');
      expect(fixture.Shadow.first.privateName(), 'private');
      expect(fixture.Shadow.first.toString(), 'Shadow.first');
      expect(fixture.bound(fixture.Simple.second)(), 'Simple.second');
    },
  );
}
