final trace = <String>[];

class Base {
  String stored = '';
  String echo(String value, String ignored) {
    trace.add('base:echo');
    return value;
  }

  String get read {
    trace.add('base:read');
    return stored;
  }

  set write(String value) {
    trace.add('base:write');
    stored = value;
  }

  String operator +(String value) {
    trace.add('base:+');
    return value;
  }

  String operator [](String value) {
    trace.add('base:[]');
    return value;
  }

  void operator []=(String key, String value) {
    trace.add('base:[]=');
    stored = value;
  }
}

class Prefix extends Base {
  @override
  String echo(String value, String ignored) {
    trace.add('prefix:echo');
    return value;
  }

  @override
  String get read {
    trace.add('prefix:read');
    return stored;
  }

  @override
  set write(String value) {
    trace.add('prefix:write');
    stored = value;
  }

  @override
  String operator +(String value) {
    trace.add('prefix:+');
    return value;
  }

  @override
  String operator [](String value) {
    trace.add('prefix:[]');
    return value;
  }

  @override
  void operator []=(String key, String value) {
    trace.add('prefix:[]=');
    stored = value;
  }
}

mixin Prior on Base {
  @override
  String echo(String value, String ignored) {
    trace.add('prior:echo');
    return value;
  }

  @override
  String get read {
    trace.add('prior:read');
    return stored;
  }

  @override
  set write(String value) {
    trace.add('prior:write');
    stored = value;
  }

  @override
  String operator +(String value) {
    trace.add('prior:+');
    return value;
  }

  @override
  String operator [](String value) {
    trace.add('prior:[]');
    return value;
  }

  @override
  void operator []=(String key, String value) {
    trace.add('prior:[]=');
    stored = value;
  }
}

mixin Probe on Base {
  @override
  String echo(String value, String ignored) => ignored;
  String invoke(String input, String ignored) => super.echo(input, ignored);
  String bound(String input, String ignored) {
    final callback = super.echo;
    return callback(input, ignored);
  }

  String getter(String input) {
    stored = input;
    return super.read;
  }

  String setter(String input) {
    super.write = input;
    return stored;
  }

  String plus(String input) => super + input;
  String index(String input) => super[input];
  String indexSet(String input) {
    super['key'] = input;
    return stored;
  }
}

class Direct extends Base with Probe {}

class Applied extends Prefix with Probe {}

class Stacked extends Base with Prior, Probe {}

class Named = Prefix with Probe;

class End extends Applied {
  @override
  String echo(String value, String ignored) => ignored;
}
