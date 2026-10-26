import frida
import sys
import os
import json
import socket
import time
import subprocess

host = 'localhost'  # Java 服务器的主机名或 IP 地址
recv_port = 13338         # 与 Java client相同的端口号
send_port = 13337         # 与 Java 服务器相同的端口号
socket_client = None
cnt = 0
activity_list = set()
detached = False

def create_socket_server():
    try:
        server_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        # the harness restarts this script between runs; do not wait for TIME_WAIT
        server_socket.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        server_socket.bind((host, recv_port))
        server_socket.listen(5)
        print(f"Listening on {host}:{recv_port}...")

        client_socket, addr = server_socket.accept()
        print(f"Connection established with {addr}")

        while True:
            message = client_socket.recv(1024).decode('utf-8')
            if message:
                print(f"Received message: {message}")
                break

        client_socket.close()
    except Exception as e:
        print(f"Error: {str(e)}")


def connect_socket_server():
    try:
        client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client_socket.connect((host, send_port))
        print("Connected to Java Socket Server")
        return client_socket
    except Exception as e:
        print(f"Error: {str(e)}")


def close_socket_connect(client_socket):
    try:
        client_socket.close()
        print("Close Java Socket Server")
    except Exception as e:
        print(f"Error: {str(e)}")


def send_signal(client_socket, data):
    try:
        client_socket.send(b"trigger : %b \n" % data.encode('utf-8'))
    except Exception as e:
        print(f"Error: {str(e)}")


def on_message(message, data):
    global cnt, socket_client, activity_list
    if message['type'] == 'send':
        print("[*] {0}".format(message['payload']))
        payload = message['payload']
        tag = payload.get('tag')
        data = payload.get('data')
        if(tag == 'trigger'):
            # socket通信
            print(f"trigger: {data}")
            #todo:把触发的activity统计起来
            activity_list.add(data)
            # cnt += 1
            send_signal(socket_client, data)
    else:
        print(message)


def on_detached(reason, *args):
    global detached
    detached = True
    print(f"[*] detached from the app: {reason}")


def ret_json(json_path):
    json = ""
    with open(json_path, "r") as f:
        json = f.read()
    return json


def generate_hook_js(js_path, json):
    jscode = '''
    Java.perform(function(){{
    var target = Java.use(\"{component_name}\");
    target.{func_name}.overload({args_fmt}).implementation = function({args_name}){{
        console.log(\"trigger \" + \"{component_name} {func_name} {args_name}\");
        send({{tag:\"trigger\", data: this.getClass().getName()}});
        var ret = null;
        ret = target.{func_name}.overload({args_fmt}).call(this, {args_name});
        return ret;
        }};
    }}
    );
    '''
    with open(js_path, "w") as f:
        for data in json['hook_api']:
            args_fmt_list = data['args_fmt']
            args_fmt_str = ', '.join([f'"{item}"' for item in args_fmt_list])
            args_name_list = data['args_name']
            args_name_str = ', '.join(args_name_list)
            f.write(jscode.format(component_name=data['component_name'],
                                  func_name=data['func_name'], args_fmt=args_fmt_str, args_name=args_name_str))


def run():
    global script
    api_json = json.loads(ret_json("./api.json"))
    config_json = json.loads(ret_json("../configures/config.json"))
    package_name = config_json['PACKAGE_NAME']
    generate_hook_js("hook.js", api_json)
    jscode = ""
    with open("hook.js", "r") as f:
        jscode = f.read()

    devices = frida.get_usb_device(timeout=30)

    pid = 0
    for app in devices.enumerate_applications():
        if app.identifier == package_name:
            pid = app.pid
            break
    
    if pid == 0:
        print("The app is not running")
        return
    process = devices.attach(pid)
    process.on('detached', on_detached)
    script = process.create_script(jscode)
    script.on('message', on_message)
    print('[*] Running')
    script.load()
    # the original busy-waited (`while(1): pass`); sleep until the harness stops this script or the app goes away
    while not detached:
        time.sleep(1)

def test():
    # generate_hook_js("hook.js", json.loads(ret_json("api.json")))
    devices = frida.get_usb_device()
    app = devices.enumerate_applications()
    print(app)

if __name__ == "__main__":
    #connect socket with java client
    create_socket_server()
    #wait java server ready
    time.sleep(2)
    socket_client = connect_socket_server()
    cnt = 0
    #run hook
    try:
        run()
    except Exception as e:
        print(f"Error: {str(e)}")
    close_socket_connect(socket_client)
    print(activity_list)



