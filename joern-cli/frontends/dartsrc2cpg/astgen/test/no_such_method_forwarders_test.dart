import 'package:test/test.dart';

import '../../src/test/resources/semantics/no_such_method_forwarders.dart'
    as fixture;

void main() {
  test('noSuchMethod forwarding preserves input and constant handlers', () {
    for (final input in ['first', 'independent']) {
      expect(fixture.forwarding(input), input);
      expect(fixture.bound(input), input);
      expect(fixture.generic(input), input);
      expect(fixture.picked(input), input);
      expect(fixture.supplied(input), input);
      expect(fixture.constant(input), 'constant');
      expect(fixture.concrete(input), 'concrete');
    }
  });

  test('forwarders preserve invocation shape, defaults and receiver state', () {
    final left = fixture.Inherited();
    final right = fixture.Inherited();
    expect(left.pick('first'), 'first');
    final picked = left.invocations.single;
    expect(picked.memberName, #pick);
    expect(picked.isMethod, isTrue);
    expect(picked.isGetter, isFalse);
    expect(picked.isSetter, isFalse);
    expect(picked.positionalArguments, ['first']);
    expect(picked.namedArguments, {#label: 'interface'});
    expect(picked.typeArguments, isEmpty);
    expect(
      () => picked.positionalArguments.add('changed'),
      throwsUnsupportedError,
    );
    expect(
      () => picked.namedArguments[#label] = 'changed',
      throwsUnsupportedError,
    );
    expect(left.pick('second', label: 'supplied'), 'second');
    expect(left.invocations.last.namedArguments, {#label: 'supplied'});
    expect(left.echo<String>('generic'), 'generic');
    expect(left.invocations.last.memberName, #echo);
    expect(left.invocations.last.typeArguments, [String]);
    expect(
      () => left.invocations.last.typeArguments.add(int),
      throwsUnsupportedError,
    );
    left.title = 'stored';
    final setter = left.invocations.last;
    expect(setter.memberName, const Symbol('title='));
    expect(setter.isSetter, isTrue);
    expect(setter.isMethod, isFalse);
    expect(setter.isGetter, isFalse);
    expect(setter.positionalArguments, ['stored']);
    expect(setter.namedArguments, isEmpty);
    expect(left.title, 'stored');
    expect(left.invocations.last.memberName, #title);
    expect(left.invocations.last.isGetter, isTrue);
    expect(left.invocations.last.positionalArguments, isEmpty);
    expect(right.title, 'initial');
    final concrete = fixture.Concrete();
    expect(concrete.pick('ignored'), 'concrete');
    expect(concrete.invocations, isEmpty);
  });
}
