(function () {
    globalThis.bridgeFixture = {
        marker: "fixture-ready",
        count: 1
    };
    globalThis.fixtureOrderedValue = "loaded-before-bridge";
    Object.defineProperty(globalThis, "bridgeThrowingSetter", {
        configurable: true,
        set: function () {
            throw new TypeError("bridge setter boom");
        }
    });
    globalThis.bridgeRoot = {
        child: { value: 10 },
        retained: { value: "still-live" }
    };
    globalThis.bridgeAlias = bridgeRoot.child;
    globalThis.bridgeSymbol = Symbol("unsupported");
    if (typeof BigInt === "function") {
        globalThis.bridgeBigInt = BigInt(42);
    }
    globalThis.bridgeGlobalCall = function () {
        return {
            receiverIsGlobal: this === globalThis,
            count: arguments.length,
            joined: Array.prototype.join.call(arguments, "|")
        };
    };
    globalThis.bridgeMethodObject = {
        value: 4,
        multiply: function (factor, increment) {
            this.value = this.value * factor + increment;
            return this.value;
        },
        replaceable: function () { return "first"; },
        returnArgument: function (object) {
            object.changedByCall = true;
            return object;
        },
        neverSettlingPromise: function () {
            return new Promise(function () {});
        },
        throwError: function () {
            throw new RangeError("method boom");
        },
        throwString: function () { throw "string boom"; },
        throwNumber: function () { throw 123; },
        throwNull: function () { throw null; },
        throwObject: function () { throw { message: "object boom" }; },
        throwBadDiagnostic: function () {
            var thrown = {};
            Object.defineProperty(thrown, "message", {
                get: function () { throw new Error("diagnostic boom"); }
            });
            throw thrown;
        }
    };
    Object.defineProperty(globalThis, "bridgeThrowingGetter", {
        configurable: true,
        get: function () { throw new SyntaxError("getter boom"); }
    });
    Object.defineProperty(globalThis, "bridgeReadOnly", {
        configurable: true,
        writable: false,
        value: 1
    });
    globalThis.bridgeRejectingProxy = new Proxy({}, {
        set: function () { return false; }
    });
    console.log("BRIDGE_TEST_FIXTURE_READY");
})();
