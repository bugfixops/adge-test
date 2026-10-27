
    Java.perform(function(){
    var target = Java.use("android.app.Activity");
    target.onCreate.overload("android.os.Bundle").implementation = function(bundle){
        console.log("trigger " + "android.app.Activity onCreate bundle");
        send({tag:"trigger", data: this.getClass().getName()});
        var ret = null;
        ret = target.onCreate.overload("android.os.Bundle").call(this, bundle);
        return ret;
        };
    }
    );
    