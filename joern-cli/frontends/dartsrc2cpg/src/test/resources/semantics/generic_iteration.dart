import 'synchronous_iteration.dart';

class InheritedValues<T> extends Values<T> {
  InheritedValues(super.items);
}

class RecursiveValues<T extends RecursiveValues<T>> extends Values<T> {
  RecursiveValues(super.items);
}

class Leaf extends RecursiveValues<Leaf> {
  Leaf() : super([]);
}

List<String> bounded<T extends Values<String>>(T values) => [
  for (final value in values) value,
];

List<E> chained<E, V extends Values<E>, T extends V>(T values) => [
  for (final value in values) value,
];

List<E> symbolic<E>(Values<E> values) => [for (final value in values) value];

List<T> recursive<T extends RecursiveValues<T>>(T values) => [
  for (final value in values) value,
];

List<String> inherited(InheritedValues<String> values) => [
  for (final value in values) value,
];

List<String> nullableBound<T extends Values<String>?>(T values) {
  if (values == null) return [];
  return [for (final value in values) value];
}

List<String> boundedInterface<T extends Iterable<String>>(T values) => [
  for (final value in values) value,
];

class BoundedIteratorValues<E, I extends Cursor<E>> extends Iterable<E> {
  final I cursor;
  BoundedIteratorValues(this.cursor);

  @override
  I get iterator {
    trace.add('iterator');
    return cursor;
  }
}

List<E> iteratorBound<E, I extends Cursor<E>>(
  BoundedIteratorValues<E, I> values,
) => [for (final value in values) value];

List<E> chainedIteratorBound<E, I extends Cursor<E>, J extends I>(
  BoundedIteratorValues<E, J> values,
) => [for (final value in values) value];

List<String> concreteIteratorBound(
  BoundedIteratorValues<String, Cursor<String>> values,
) => [for (final value in values) value];
