import uiautomator2 as u2
import sys
import os
import json
import socket
import time
import subprocess

host = 'localhost'  # Java 服务器的主机名或 IP 地址
recv_port = 13332         # 与 Java client相同的端口号
send_port = 13331         # 与 Java 服务器相同的端口号
socket_client = None
d = None

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
            # the original used str.index, which raises instead of returning -1; an empty read means Java closed
            if not message:
                break
            #包含dump
            if "dump" in message:
                dump()
                send_signal(socket_client, "dumped")
            elif "exit" in message:
                break
        client_socket.close()
    except Exception as e:
        print(f"Error: {str(e)}")


def connect_socket_server():
    try:
        client_socket = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
        client_socket.settimeout(None)
        print("Connecting to Java Socket Server...")
        while True:
            try:
                client_socket.connect((host, send_port))
                print("Connected to Java Socket Server")
                break
            except Exception as e:
                print(f"Error: {str(e)}")
                time.sleep(1)
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
        client_socket.send(b"%b \n" % data.encode('utf-8'))
    except Exception as e:
        print(f"Error: {str(e)}")


def dump():
    try:
        d.dump_hierarchy()
        time.sleep(0.5)
        xml = d.dump_hierarchy()
    except Exception as e:
        # keep the explorer going: an empty hierarchy makes it press back
        print(f"Error: dump failed: {str(e)}")
        xml = '<?xml version="1.0" encoding="UTF-8"?><hierarchy rotation="0"></hierarchy>'
    with open("../window_dump.xml", "w", encoding='utf-8') as f:
        f.write(xml)
    

if __name__ == "__main__":
    try:
        d = u2.connect()
        socket_client = connect_socket_server()
        create_socket_server()
        close_socket_connect(socket_client)
    except Exception as e:
        print(f"Error: {str(e)}")
        close_socket_connect(socket_client)
        sys.exit(1)
    
