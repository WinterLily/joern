class Data {
  final String value;
  Data(this.value);
  String read() => value;
}

class Box<T extends Data> {
  T value;
  Box(this.value);
  T echo(T input) => input;
  U narrower<U extends T>(U input) => input;
  String read(T input) => input.read();
}

class Other<T extends num> {
  T echo(T input) => input;
}

class Unbounded<T> {
  T? optional(T? input) {
    T? saved = input;
    return saved;
  }
}

class Recursive<T extends Comparable<T>> {}

T first<T extends Data>(T input) => input;
T second<T extends num>(T input) => input;

String nested<T extends Data>(T outer) {
  T helper<T extends num>(T inner) => inner;
  return '${outer.read()}:${helper(7)}';
}

typedef Converter<T extends Data> = T Function(T input);
typedef T Legacy<T extends Data>(T input);
typedef Pair<U extends Data, T extends U> = (U, T);
